package deposit.service;

import deposit.common.*;
import deposit.repository.CoreRepository;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.neo4j.graphdb.Node;

/** Staging and immutable package grants shared by export, intake and import. */
final class ExchangePackages extends BaseService {
    static final String LABEL="DepositExchangeStage", MEMBER="DepositExchangeUploadMember";
    static final String SOURCE="source-export", RECEIVING="receiving-upload";
    private static final long RETENTION=ResourceFiles.LEASE, READER=15L*60*1000;
    private static final Set<String> MEMBERS=Set.of("manifest","graph","history");
    private final ResourceFiles files;

    ExchangePackages(RequestContext c) {
        super(c);
        files=c.server.storage.files;
    }

    Map<String,Object> execute(String procedure,Map<String,Object> request) {
        try {
            return switch(procedure) {
                case "deposit.exchange.beginUpload" -> begin(request);
                case "deposit.exchange.uploadChunk" -> chunk(request);
                case "deposit.exchange.finishUpload" -> finish(request);
                case "deposit.exchange.abortUpload" -> abort(request);
                case "deposit.exchange.inspect" -> inspect(request);
                case "deposit.exchange.memberPage" -> page(request,"files");
                case "deposit.exchange.graphPage" -> page(request,string(request.get("collection")));
                case "deposit.exchange.readChunk" -> read(request);
                case "deposit.exchange.discard" -> discard(request);
                default -> throw new IllegalArgumentException(procedure);
            };
        } catch(StorageAdmission.StoragePressureException failure) {
            throw ExceptionHandler.fail("LIMIT_EXCEEDED",failure.getMessage());
        } catch(ArithmeticException failure) {
            throw ExceptionHandler.fail("LIMIT_EXCEEDED","Exchange length is not representable");
        } catch(UncheckedIOException failure) {
            throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to access staged Exchange data");
        } catch(IOException failure) {
            throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to access staged Exchange data");
        }
    }

    static String relative(String id) {return "staging/exchange/"+id;}
    private long now(){return c.server.sessionTime();}
    private void checkpoint(){c.state.kernel.assertOpen();}
    private Path path(Node stage,String name)throws IOException {return files.path(relative(CoreRepository.id(stage))+"/"+name);}

    static void validateBegin(List<Object> arguments) {
        String kind=string(arguments.get(1));
        if(!(arguments.get(2) instanceof Map<?,?> raw))
            throw ExceptionHandler.fail("VALIDATION_FAILED","Exchange metadata must be a map");
        var metadata=ValueCodec.map(raw);
        if(kind.equals("zip") && metadata.isEmpty())return;
        if(kind.equals("snapshot") && metadata.keySet().equals(Set.of("includeHistory"))
                && metadata.get("includeHistory") instanceof Boolean)return;
        throw ExceptionHandler.fail("VALIDATION_FAILED","Invalid Exchange input kind or metadata");
    }

    String writerScope(Object raw) {
        Node stage=repo.find(LABEL,"id",RequestContext.uuid(raw));
        if(stage==null || !c.actorId.equals(stage.getProperty("actorId",null)) || !stage.hasProperty("inputKind"))throw unavailable();
        return (String)stage.getProperty("scope");
    }

    Node writer(Object raw,String scope,boolean abort) {
        String id=RequestContext.uuid(raw);
        writerScope(id);
        Node stage=repo.find(LABEL,"id",id);
        if(stage==null || !scope.equals(stage.getProperty("scope",null)))throw unavailable();
        String state=(String)stage.getProperty("state");
        if(!state.equals("uploading") && !(abort && state.equals("aborted")))
            throw ExceptionHandler.fail("UPLOAD_FENCED","Exchange writer is unavailable");
        UploadBinding.require(c,stage);
        if(c.state.operationClaimed)c.checkSourceAuthorization=()->UploadBinding.require(c,stage);
        if(!abort) {
            try(var attempts=repo.scanNodes("DepositOperationAttempt",Map.of("uploadId",id))) {
                while(attempts.hasNext()) {
                    var attempt=attempts.next();
                    if(!Objects.equals(c.operationId,attempt.getProperty("operationId"))
                            && UploadBinding.failedStep(c.server,attempt.getAllProperties()))
                        throw ExceptionHandler.fail("UPLOAD_FENCED","Exchange upload was interrupted. Start a new upload");
                }
            }
        }
        return stage;
    }

    private void lockReceiving(String scope) {
        repo.lockActor(c.actorId);
        if(!"server".equals(scope)) {
            scoped("editor");
            if(!scope.equals(c.namespaceId))throw unavailable();
            repo.lockNamespace(scope);
            requireRole(scope,"editor");
        }
    }

    private Map<String,Object> begin(Map<String,Object> request)throws IOException {
        validateBegin(Arrays.asList(request.get("scope"),request.get("inputKind"),request.get("metadata")));
        String scope=c.actionScope;
        lockReceiving(scope);
        Node stage=create(scope,"uploading");
        stage.setProperty("inputKind",request.get("inputKind"));
        stage.setProperty("includeHistory",Boolean.TRUE.equals(ValueCodec.map(request.get("metadata")).get("includeHistory")));
        UploadBinding.bind(c,stage);
        return Json.map("uploadId",CoreRepository.id(stage),"expiresAt",now()+RETENTION);
    }

    private Node create(String scope,String state)throws IOException {
        String id=UUID.randomUUID().toString();
        String directory=relative(id);
        var intent=Json.map("phase","exchange-stage","actorId",c.actorId,"receiptScope",scope,"stagingPath",directory);
        var owner=c.server.storage.admission.acquire("exchange-stage",id,c.operationId,"exchange-stage",Map.of(files.root,0L),intent);
        Path root=files.path(directory);
        boolean[] created={false},committed={false};
        c.state.afterCommit.add(()->committed[0]=true);
        c.state.release.add(()->{
            if(!committed[0]) {
                try {
                    if(created[0])files.deleteOwnedStagingTree(directory);
                    c.server.storage.admission.close(owner);
                } catch(IOException failure){c.server.storage.diagnostic="Exchange stage cleanup pending";}
            }
        });
        Files.createDirectories(root.getParent());
        Files.createDirectory(root);
        created[0]=true;
        return repo.createRestored(LABEL,null,c.actorId,Json.map("actorId",c.actorId,"scope",scope,"state",state,"admissionId",owner.id),id);
    }

    private Map<String,Object> chunk(Map<String,Object> request)throws IOException {
        String scope=c.actionScope;
        lockReceiving(scope);
        Node stage=writer(request.get("uploadId"),scope,false);
        c.tx.acquireWriteLock(stage);
        stage=writer(request.get("uploadId"),scope,false);
        String id=CoreRepository.id(stage);
        String name=memberName(stage,request.get("member"));
        long offset=integer(request.get("offset"));
        String encoded=string(request.get("bytesBase64"));
        if(encoded.length()>4L*((ResourceFiles.MAX_CHUNK+2)/3))c.fail("LIMIT_EXCEEDED","Exchange chunk exceeds limit");
        byte[] bytes;
        try {bytes=Base64.getDecoder().decode(encoded);}
        catch(IllegalArgumentException failure){throw ExceptionHandler.fail("VALIDATION_FAILED","Invalid Base64");}
        if(bytes.length>ResourceFiles.MAX_CHUNK)c.fail("LIMIT_EXCEEDED","Exchange chunk exceeds limit");
        if(!ResourceFiles.hash(request.get("chunkSha256")).equals(ResourceFiles.checksum(bytes)))
            c.fail("VALIDATION_FAILED","Exchange chunk checksum mismatch");
        Node member=repo.find(MEMBER,Map.of("uploadId",id,"name",name));
        long previous=member==null?0L:(long)member.getProperty("bytes");
        boolean empty=bytes.length==0;
        if(offset!=previous || empty && (member!=null || !name.startsWith("resources/")))
            c.fail("VALIDATION_FAILED","Exchange member offset or chunk length is invalid");
        long next=Math.addExact(offset,bytes.length);
        var digest=member==null?new SHA256Digest():new SHA256Digest((byte[])member.getProperty("digestState"));
        Path staged=path(stage,stagedFile(name));
        if(member!=null)verifyTail(staged,previous,(String)member.getProperty("tailSha256"));

        var admission=c.server.storage.admission;
        var reservation=admission.acquire("exchange-write",UUID.randomUUID().toString(),c.operationId,"exchange-chunk",
            Map.of(files.root,(long)bytes.length,c.server.database().databaseLayout().getTransactionLogsDirectory(),65536L),Map.of());
        try {
            files.append(relative(id)+"/"+stagedFile(name),offset,bytes);
            updateDigest(digest,staged,offset-offset%64,next-next%64);
            if(member==null)member=repo.create(MEMBER,null,c.actorId,Json.map("uploadId",id,"name",name));
            member.setProperty("bytes",next);
            member.setProperty("digestState",digest.getEncodedState());
            member.setProperty("tailSha256",tailDigest(staged,next));
        } finally {admission.close(reservation);}
        return Json.map("uploadId",id,"member",name,"nextOffset",next);
    }

    private static String memberName(Node stage,Object raw) {
        String name=string(raw);
        if("zip".equals(stage.getProperty("inputKind"))) {
            if(name.equals("package"))return name;
        } else if(MEMBERS.contains(name) && (!name.equals("history") || Boolean.TRUE.equals(stage.getProperty("includeHistory")))
                || resource(name))return name;
        throw ExceptionHandler.fail("VALIDATION_FAILED","Unsupported Exchange upload member");
    }
    private static boolean resource(String name){return name.matches("resources/[a-f0-9]{64}");}
    private static String stagedFile(String name){return name.equals("package")?"package.zip":"input/"+name;}

    private ExchangePackageCodec.StagedMember member(Node stage,String name,boolean required)throws IOException {
        Node member=repo.find(MEMBER,Map.of("uploadId",CoreRepository.id(stage),"name",name));
        if(member==null) {
            if(required)throw malformed();
            return null;
        }
        return member(nodeProperties(member),stage);
    }
    private record MemberRecord(String name,long size,byte[] digest,String tailSha256) {}
    private static MemberRecord nodeProperties(Node member) {
        return new MemberRecord((String)member.getProperty("name"),(long)member.getProperty("bytes"),
            (byte[])member.getProperty("digestState"),(String)member.getProperty("tailSha256"));
    }
    private ExchangePackageCodec.StagedMember member(MemberRecord record,Node stage)throws IOException {
        var digest=new SHA256Digest(record.digest());
        Path path=path(stage,stagedFile(record.name()));
        verifyTail(path,record.size(),record.tailSha256());
        updateDigest(digest,path,record.size()-record.size()%64,record.size());
        var sha=new byte[32];
        digest.doFinal(sha,0);
        return new ExchangePackageCodec.StagedMember(record.name(),path,record.size(),HexFormat.of().formatHex(sha));
    }

    private void verifyTail(Path path,long size,String expected)throws IOException {
        if(!expected.equals(tailDigest(path,size)))throw new IOException("Previously verified Exchange bytes differ");
    }

    private static String tailDigest(Path path,long size)throws IOException {
        int length=(int)(size%64);
        try(var file=new RandomAccessFile(path.toFile(),"r")){
            file.seek(size-length);
            byte[] tail=new byte[length];
            file.readFully(tail);
            return ResourceFiles.checksum(tail);
        }
    }

    private void updateDigest(SHA256Digest digest,Path path,long start,long end)throws IOException {
        // Persist SHA state only on a block boundary. Pending bytes stay in
        // the private staging file instead of a Neo4j property.
        if(start==end)return;
        try(var channel=FileChannel.open(path,StandardOpenOption.READ)){
            channel.position(start);
            var buffer=ByteBuffer.allocate((int)Math.min(end-start,64*1024));
            for(long remaining=end-start;remaining>0;){
                checkpoint();
                buffer.clear().limit((int)Math.min(buffer.capacity(),remaining));
                int count=channel.read(buffer);
                if(count<0)throw new EOFException("Staged Exchange member ended before its verified offset");
                digest.update(buffer.array(),0,count);
                remaining-=count;
            }
        }
    }

    private Map<String,Object> finish(Map<String,Object> request)throws IOException {
        String scope=c.actionScope;
        lockReceiving(scope);
        Node stage=writer(request.get("uploadId"),scope,false);
        c.tx.acquireWriteLock(stage);
        writer(request.get("uploadId"),scope,false);
        String id=CoreRepository.id(stage);
        Path archive=path(stage,"package.zip");
        var finished=new ExchangeBudget(c,"exchange-finish");
        try {
            if("zip".equals(stage.getProperty("inputKind")))member(stage,"package",true).verify(this::checkpoint);
            else {
                try(var records=repo.scanNodes(MEMBER,Map.of("uploadId",id))) {
                    Iterable<ExchangePackageCodec.StagedMember> resources=()->records.stream()
                        .filter(row->resource((String)row.getProperty("name"))).map(row->{
                            try {return member(nodeProperties(row),stage);}
                            catch(IOException failure){throw new UncheckedIOException(failure);}
                        }).iterator();
                    ExchangePackageCodec.writeSnapshot(archive,member(stage,"manifest",true),member(stage,"graph",true),
                        member(stage,"history",false),resources,(boolean)stage.getProperty("includeHistory"),finished,this::checkpoint);
                }
            }
            var reference=publish(stage,RECEIVING,false,finished);
            stage.setProperty("inputCleanup",true);
            return reference;
        } catch(IOException|UncheckedIOException failure) {
            throw malformed();
        } finally {finished.close();}
    }

    private Map<String,Object> abort(Map<String,Object> request) {
        String scope=c.actionScope;
        lockReceiving(scope);
        Node stage=writer(request.get("uploadId"),scope,true);
        c.tx.acquireWriteLock(stage);
        String id=CoreRepository.id(stage);
        if("uploading".equals(stage.getProperty("state"))) {
            stage.setProperty("state","aborted");
            stage.setProperty("cleanupAfter",now());
            stage.setProperty("removeAfter",now()+RETENTION);
        }
        return Json.map("uploadId",id,"state","aborted");
    }

    Node createExport()throws IOException {return create(c.namespaceId,"creating");}
    Path archive(Node stage)throws IOException {return path(stage,"package.zip");}
    Path content(Node stage)throws IOException {return path(stage,"content");}
    Map<String,Object> publish(Node stage,String kind,boolean history,ExportRecords.StorageBudget budget)throws IOException {
        Path archive=archive(stage);
        var index=path(stage,"content");
        long count=ExchangePackageIndex.build(archive,index,budget,this::checkpoint);
        String digest=ResourceFiles.checksum(archive,this::checkpoint);
        long bytes=Files.size(archive);
        try(var channel=FileChannel.open(archive,StandardOpenOption.WRITE)){channel.force(true);}
        long expires=now()+RETENTION;
        String grant=UUID.randomUUID().toString();
        stage.setProperty("grant",grant);
        stage.setProperty("grantKind",kind);
        stage.setProperty("history",history);
        stage.setProperty("state","ready");
        stage.setProperty("bytes",bytes);
        stage.setProperty("sha256",digest);
        stage.setProperty("expiresAt",expires);
        stage.setProperty("cleanupAfter",Math.addExact(expires,READER));
        stage.setProperty("removeAfter",Math.addExact(expires,READER));
        return Json.map("packageId",CoreRepository.id(stage),"grant",grant,"bytes",bytes,"sha256",digest,"expiresAt",expires,"memberCount",count);
    }

    static Map<String,Object> source(Object raw) {
        var source=ValueCodec.map(raw);
        if(!source.keySet().equals(Set.of("packageId","grant")))
            throw ExceptionHandler.fail("VALIDATION_FAILED","Exchange source requires packageId and grant");
        return source;
    }

    Node input(String id,String grant,String receivingScope,boolean acquiring,boolean pin) {
        Node stage=access(id,grant);
        if(pin)c.tx.acquireReadLock(stage);
        authorize(stage,grant,"editor");
        if(!"ready".equals(stage.getProperty("state",null)) || acquiring && (long)stage.getProperty("expiresAt",0L)<=now())
            throw unavailable();
        String scope=(String)stage.getProperty("scope");
        if(RECEIVING.equals(stage.getProperty("grantKind")) && !scope.equals(receivingScope))throw unavailable();
        return stage;
    }

    void authorizeResultSource(String id) {
        Node stage=repo.find(LABEL,"id",id);
        if(stage==null)throw unavailable();
        c.tx.acquireReadLock(stage);
        if(!SOURCE.equals(stage.getProperty("grantKind",null))
                || !"ready".equals(stage.getProperty("state",null))
                || (long)stage.getProperty("expiresAt",0L)<=now())throw unavailable();
        authorize(stage,stage.getProperty("grant"),"viewer");
    }

    boolean sourceExport(Node stage) {return SOURCE.equals(stage.getProperty("grantKind",null));}

    private Node access(Object rawId,Object rawGrant) {
        Node stage=repo.find(LABEL,"id",RequestContext.uuid(rawId));
        if(stage==null || !c.actorId.equals(stage.getProperty("actorId",null))
                || !(rawGrant instanceof String grant) || !grant.equals(stage.getProperty("grant",null)))throw unavailable();
        return stage;
    }

    private void authorize(Node stage,Object rawGrant,String receivingRole) {
        if(!c.actorId.equals(stage.getProperty("actorId",null)) || !Objects.equals(rawGrant,stage.getProperty("grant",null)))
            throw unavailable();
        String scope=(String)stage.getProperty("scope");
        if(scope.equals("server"))return;
        boolean source=SOURCE.equals(stage.getProperty("grantKind"));
        String required=source?(Boolean.TRUE.equals(stage.getProperty("history",false))?"owner":"viewer"):receivingRole;
        String current=effectiveRole(scope);
        if(current==null || repo.namespace(scope)==null || rank(current)<rank(required))throw unavailable();
    }

    private Node readStage(Map<String,Object> request) {
        Node stage=access(request.get("packageId"),request.get("grant"));
        c.tx.acquireReadLock(stage);
        authorize(stage,request.get("grant"),"viewer");
        return stage;
    }

    private Map<String,Object> inspect(Map<String,Object> request)throws IOException {
        Node stage=readStage(request);
        long now=now(), expires=(long)stage.getProperty("expiresAt",0L);
        if(!"ready".equals(stage.getProperty("state")) || now>=expires)throw unavailable();
        long deadline=now+READER;
        var envelope=ExchangePackageIndex.inspect(path(stage,ExchangePackageIndex.FILE));
        var result=new LinkedHashMap<>(envelope);
        var lease=leaseBinding(stage,deadline,UUID.randomUUID().toString());
        result.put("leaseId",CursorCodec.encode(c,lease));
        result.put("leaseExpiresAt",deadline);
        return result;
    }

    private Map<String,Object> leaseBinding(Node stage,long deadline,String nonce) {
        return Json.map("type","exchange-lease","packageId",CoreRepository.id(stage),"actorId",c.actorId,
            "scope",stage.getProperty("scope"),"grant",stage.getProperty("grant"),"expires",deadline,"nonce",nonce);
    }

    private record ReadLease(Node stage,String id,long deadline) {}

    private ReadLease lease(Map<String,Object> request) {
        Node stage=readStage(request);
        String id=string(request.get("leaseId"));
        if(id.length()>2048)throw unavailable();
        Map<String,Object> decoded;
        try {decoded=CursorCodec.decode(c,id,now());}
        catch(RuntimeException failure){throw unavailable();}
        if(!(decoded.get("expires") instanceof Long deadline) || deadline<=now()
                || !(decoded.get("nonce") instanceof String nonce) || !leaseBinding(stage,deadline,nonce).equals(decoded)
                || !Set.of("ready","discarded").contains(stage.getProperty("state")))throw unavailable();
        return new ReadLease(stage,id,deadline);
    }

    private Map<String,Object> page(Map<String,Object> request,String collection)throws IOException {
        ReadLease lease=lease(request);
        if(collection.length()>128 || "files".equals(collection)&&"deposit.exchange.graphPage".equals(c.procedure))
            throw ExceptionHandler.fail("VALIDATION_FAILED","Invalid package collection");
        long limit=request.get("limit")==null?ExchangePackageIndex.PAGE_LIMIT:integer(request.get("limit"));
        if(limit<1 || limit>ExchangePackageIndex.PAGE_LIMIT)c.fail("VALIDATION_FAILED","Invalid package page limit");
        long position=0;
        if(request.get("cursor")!=null) {
            var cursor=ValueCodec.map(request.get("cursor"));
            position=integer(cursor.get("position"));
            if(position<0 || !pageCursor(lease,collection,position).equals(cursor))
                c.fail("VALIDATION_FAILED","Invalid package page cursor");
        }
        var page=ExchangePackageIndex.page(path(lease.stage(),ExchangePackageIndex.FILE),collection,position,(int)limit);
        if(page==null || now()>=lease.deadline())throw unavailable();
        return Json.map("packageId",CoreRepository.id(lease.stage()),"collection",collection,"entries",page.entries(),
            "nextCursor",page.nextPosition()==null?null:pageCursor(lease,collection,page.nextPosition()));
    }

    private Map<String,Object> pageCursor(ReadLease lease,String collection,long position) {
        var fields=Json.map("packageId",CoreRepository.id(lease.stage()),"leaseId",lease.id(),"collection",collection,"position",position);
        String proof=CursorCodec.requestHash(c,Json.map("type","exchange-cursor","actorId",c.actorId,"cursor",fields));
        fields.put("integrity",proof);
        return fields;
    }

    private Map<String,Object> read(Map<String,Object> request)throws IOException {
        ReadLease lease=lease(request);
        String member=string(request.get("member"));
        Node stage=lease.stage();
        long offset=integer(request.get("offset")), requested=integer(request.get("length")), size;
        if(offset<0 || requested<1 || requested>ResourceFiles.MAX_CHUNK)c.fail("VALIDATION_FAILED","Invalid Exchange read range");
        Path path;
        if(member.equals("package")) {
            size=(long)stage.getProperty("bytes");
            path=archive(stage);
        } else {
            if(!resource(member))throw unavailable();
            Long bytes=ExchangePackageIndex.memberBytes(path(stage,ExchangePackageIndex.FILE),member);
            if(bytes==null)throw unavailable();
            size=bytes;
            path=path(stage,"content/"+member.substring(10));
        }
        if(offset>size)c.fail("VALIDATION_FAILED","Invalid Exchange read offset");
        var bytes=ByteBuffer.allocate((int)Math.min(requested,size-offset));
        try(var channel=FileChannel.open(path,StandardOpenOption.READ)) {
            if(channel.size()!=size)throw new IOException("Exchange member length differs");
            channel.position(offset);
            while(bytes.hasRemaining()) {
                checkpoint();
                if(now()>=lease.deadline())throw unavailable();
                if(channel.read(bytes)<0)throw new EOFException();
            }
        }
        if(now()>=lease.deadline())throw unavailable();
        return Json.map("offset",offset,"length",(long)bytes.capacity(),"bytesBase64",Base64.getEncoder().encodeToString(bytes.array()));
    }

    private Map<String,Object> discard(Map<String,Object> request) {
        if(c.state.mutation||c.state.selection)c.fail("VALIDATION_FAILED","Exchange discard requires a separate transaction");
        c.state.mutation=true;
        repo.lockActor(c.actorId);
        Node stage=access(request.get("packageId"),request.get("grant"));
        c.tx.acquireWriteLock(stage);
        authorize(stage,request.get("grant"),"viewer");
        if("ready".equals(stage.getProperty("state"))) {
            stage.setProperty("state","discarded");
            stage.setProperty("cleanupAfter",Math.min((long)stage.getProperty("cleanupAfter"),now()+READER));
        }
        return Json.map("packageId",CoreRepository.id(stage),"state","discarded");
    }

    private static RuntimeException unavailable() {
        return ExceptionHandler.fail("NOT_FOUND","Exchange package or upload is unavailable");
    }
    private static RuntimeException malformed() {
        return ExceptionHandler.fail("VALIDATION_FAILED","Exchange input is malformed, incomplete or inconsistent");
    }
}
