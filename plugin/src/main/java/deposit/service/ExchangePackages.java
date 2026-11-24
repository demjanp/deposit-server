package deposit.service;

import deposit.common.*;
import deposit.repository.ExchangeRepository;
import deposit.repository.ExchangeRepository.Member;
import deposit.repository.ExchangeRepository.Stage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import org.bouncycastle.crypto.digests.SHA256Digest;

/** Staging and immutable package grants shared by export, intake and import. */
final class ExchangePackages extends BaseService {
    static final String SOURCE="source-export", RECEIVING="receiving-upload";
    private static final long RETENTION=ResourceFiles.LEASE, READER=15L*60*1000;
    private static final Set<String> MEMBERS=Set.of("manifest","graph","history");
    private final ResourceFiles files;
    private final ExchangeRepository stages;

    ExchangePackages(RequestContext c) {
        super(c);
        files=c.server.storage.files;
        stages=c.server.storage.exchange;
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
    private Path path(Stage stage,String name)throws IOException {return files.path(relative(stage.id())+"/"+name);}

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
        Stage stage=stages.stage(RequestContext.uuid(raw));
        if(stage==null || !c.actorId.equals(stage.actorId()) || stage.inputKind()==null)throw unavailable();
        return stage.scope();
    }

    Stage writer(Object raw,String scope,boolean abort) {
        return checkWriter(writerNode(raw,scope),abort);
    }

    private Stage writerNode(Object raw,String scope) {
        String id=RequestContext.uuid(raw);
        stages.requireSettled(id,c.server.operationalUnit(c.state.kernel));
        Stage stage=stages.stage(id);
        if(stage==null || !c.actorId.equals(stage.actorId())
                || stage.inputKind()==null || !scope.equals(stage.scope()))throw unavailable();
        return stage;
    }

    private Stage checkWriter(Stage stage,boolean abort) {
        String state=stage.state();
        if(!state.equals("uploading") && !(abort && state.equals("aborted"))
                || !abort && stage.expiresAt()<=now())
            throw ExceptionHandler.fail("UPLOAD_FENCED","Exchange writer is unavailable");
        UploadBinding.requireUpload(c,stage.id(),stage.writerBound());
        if(c.state.operationClaimed)c.checkSourceAuthorization=()->UploadBinding.requireUpload(c,stage.id(),stage.writerBound());
        if(!abort) {
            for(var attempt:c.server.storage.attempts.uploadAttempts(stage.id())) {
                if(!Objects.equals(c.operationId,attempt.key().id()) && UploadBinding.failedStep(c.server,attempt))
                    throw ExceptionHandler.fail("UPLOAD_FENCED","Exchange upload was interrupted. Start a new upload");
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
        long expires = now()+RETENTION;
        var stage=create(scope,"uploading",string(request.get("inputKind")),
            Boolean.TRUE.equals(ValueCodec.map(request.get("metadata")).get("includeHistory")),expires);
        UploadBinding.bindStaging(c,stage.id());
        stages.save(stage,unit(scope));
        return Json.map("uploadId",stage.id(),"expiresAt",expires);
    }

    private Stage create(String scope,String state,String kind,boolean history,long expiry)throws IOException {
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
        return Stage.open(id,c.actorId,scope,state,owner.id,kind,history,c.state.operationClaimed && kind!=null,expiry);
    }

    private Map<String,Object> chunk(Map<String,Object> request)throws IOException {
        String scope=c.actionScope;
        lockReceiving(scope);
        c.server.lockExchange(c.state.kernel,RequestContext.uuid(request.get("uploadId")));
        Stage stage=writerNode(request.get("uploadId"),scope);
        checkWriter(stage,false);
        String id=stage.id();
        String name=memberName(stage,request.get("member"));
        long offset=integer(request.get("offset"));
        String encoded=string(request.get("bytesBase64"));
        if(encoded.length()>4L*((ResourceFiles.MAX_CHUNK+2)/3))c.fail("LIMIT_EXCEEDED","Exchange chunk exceeds limit");
        byte[] bytes;
        try {bytes=Base64.getDecoder().decode(encoded);}
        catch(IllegalArgumentException failure){throw ExceptionHandler.fail("VALIDATION_FAILED","Invalid Base64");}
        if(bytes.length>ResourceFiles.MAX_CHUNK)c.fail("LIMIT_EXCEEDED","Exchange chunk exceeds limit");
        Member member=stages.member(id,name);
        long previous=member==null?0L:member.size();
        boolean empty=bytes.length==0;
        if(offset!=previous || empty && (member!=null || !name.startsWith("resources/")))
            c.fail("VALIDATION_FAILED","Exchange member offset or chunk length is invalid");
        long next=Math.addExact(offset,bytes.length);
        var digest=member==null?new SHA256Digest():new SHA256Digest(member.digest());
        Path staged=path(stage,stagedFile(name));
        if(member!=null)verifyTail(staged,previous,member.tailSha256());

        var admission=c.server.storage.admission;
        var reservation=admission.acquire("exchange-write",UUID.randomUUID().toString(),c.operationId,"exchange-chunk",
            Map.of(files.root,(long)bytes.length,c.server.database().databaseLayout().getTransactionLogsDirectory(),65536L),Map.of());
        try {
            files.append(relative(id)+"/"+stagedFile(name),offset,bytes);
            updateDigest(digest,staged,offset-offset%64,next-next%64);
            stages.save(id,new Member(name,next,digest.getEncodedState(),tailDigest(staged,next)),unit(scope));
        } finally {admission.close(reservation);}
        return Json.map("uploadId",id,"member",name,"nextOffset",next);
    }

    private static String memberName(Stage stage,Object raw) {
        String name=string(raw);
        if("zip".equals(stage.inputKind())) {
            if(name.equals("package"))return name;
        } else if(MEMBERS.contains(name) && (!name.equals("history") || Boolean.TRUE.equals(stage.includeHistory()))
                || resource(name))return name;
        throw ExceptionHandler.fail("VALIDATION_FAILED","Unsupported Exchange upload member");
    }
    private static boolean resource(String name){return name.matches("resources/[a-f0-9]{64}");}
    private static String stagedFile(String name){return name.equals("package")?"package.zip":"input/"+name;}

    private ExchangePackageCodec.StagedMember member(Stage stage,String name,boolean required)throws IOException {
        Member member=stages.member(stage.id(),name);
        if(member==null) {
            if(required)throw malformed();
            return null;
        }
        return member(member,stage);
    }

    private Iterable<ExchangePackageCodec.StagedMember> resourceMembers(Stage stage) {
        return () -> new Iterator<>() {
            private Iterator<Member> page = Collections.emptyIterator();
            private String after = "resources/";
            private boolean exhausted;

            @Override public boolean hasNext() {
                if (!page.hasNext() && !exhausted) {
                    page = stages.resourceMembers(stage.id(), after).iterator();
                    exhausted = !page.hasNext();
                }
                return !exhausted;
            }

            @Override public ExchangePackageCodec.StagedMember next() {
                if (!hasNext()) throw new NoSuchElementException();
                var row = page.next();
                after = row.name();
                try { return member(row, stage); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
            }
        };
    }

    private ExchangePackageCodec.StagedMember member(Member record,Stage stage)throws IOException {
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
        // the private staging file.
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
        c.server.lockExchange(c.state.kernel,RequestContext.uuid(request.get("uploadId")));
        Stage stage=writerNode(request.get("uploadId"),scope);
        checkWriter(stage,false);
        String id=stage.id();
        Path archive=path(stage,"package.zip");
        var finished=new ExchangeBudget(c,"exchange-finish");
        try {
            ExchangePackageCodec.ArchiveIdentity identity;
            if("zip".equals(stage.inputKind())) {
                var uploaded=member(stage,"package",true);
                uploaded.verify(this::checkpoint);
                identity=new ExchangePackageCodec.ArchiveIdentity(uploaded.bytes(),uploaded.sha256());
            }
            else {
                ExchangePackageCodec.writeSnapshot(archive,member(stage,"manifest",true),member(stage,"graph",true),
                    member(stage,"history",false),resourceMembers(stage),stage.includeHistory(),finished,this::checkpoint);
                identity=ExchangePackageCodec.ArchiveIdentity.read(archive,this::checkpoint);
            }
            return publish(stage,RECEIVING,false,finished,identity);
        } catch(IOException|UncheckedIOException failure) {
            throw malformed();
        } finally {finished.close();}
    }

    private Map<String,Object> abort(Map<String,Object> request) {
        String scope=c.actionScope;
        lockReceiving(scope);
        c.server.lockExchange(c.state.kernel,RequestContext.uuid(request.get("uploadId")));
        Stage stage=writer(request.get("uploadId"),scope,true);
        String id=stage.id();
        if("uploading".equals(stage.state())) {
            long now=now();
            stages.save(stage.cleanup("aborted",now,now+RETENTION,false,false),unit(scope));
        }
        return Json.map("uploadId",id,"state","aborted");
    }

    Stage createExport()throws IOException {return create(c.namespaceId,"creating",null,false,Long.MAX_VALUE);}
    Path archive(Stage stage)throws IOException {return path(stage,"package.zip");}
    Path content(Stage stage)throws IOException {return path(stage,"content");}
    Map<String,Object> publish(Stage stage,String kind,boolean history,ExportRecords.StorageBudget budget)throws IOException {
        return publish(stage,kind,history,budget,ExchangePackageCodec.ArchiveIdentity.read(archive(stage),this::checkpoint));
    }
    private Map<String,Object> publish(Stage stage,String kind,boolean history,ExportRecords.StorageBudget budget,
            ExchangePackageCodec.ArchiveIdentity identity)throws IOException {
        Path archive=archive(stage);
        var index=path(stage,"content");
        long count=ExchangePackageIndex.build(archive,index,budget,this::checkpoint);
        try(var channel=FileChannel.open(archive,StandardOpenOption.WRITE)){channel.force(true);}
        long expires=now()+RETENTION;
        String grant=UUID.randomUUID().toString();
        stages.save(stage.ready(grant,kind,history,identity.bytes(),identity.sha256(),expires,
            Math.addExact(expires,READER)),unit(stage.scope()));
        return Json.map("packageId",stage.id(),"grant",grant,"bytes",identity.bytes(),"sha256",identity.sha256(),"expiresAt",expires,"memberCount",count);
    }

    static Map<String,Object> source(Object raw) {
        var source=ValueCodec.map(raw);
        if(!source.keySet().equals(Set.of("packageId","grant")))
            throw ExceptionHandler.fail("VALIDATION_FAILED","Exchange source requires packageId and grant");
        return source;
    }

    Stage input(String id,String grant,String receivingScope,boolean acquiring,boolean pin) {
        if(pin)c.server.lockExchange(c.state.kernel,id);
        else stages.requireSettled(id,c.server.operationalUnit(c.state.kernel));
        Stage stage=access(id,grant);
        authorize(stage,grant,"editor");
        if(!"ready".equals(stage.state()) || acquiring && stage.expiresAt()<=now())
            throw unavailable();
        String scope=stage.scope();
        if(RECEIVING.equals(stage.grantKind()) && !scope.equals(receivingScope))throw unavailable();
        return stage;
    }

    void authorizeResultSource(String id) {
        c.server.lockExchange(c.state.kernel,id);
        Stage stage=stages.stage(id);
        if(stage==null)throw unavailable();
        if(!SOURCE.equals(stage.grantKind())
                || !"ready".equals(stage.state())
                || stage.expiresAt()<=now())throw unavailable();
        authorize(stage,stage.grant(),"viewer");
    }

    boolean sourceExport(Stage stage) {return SOURCE.equals(stage.grantKind());}

    private Stage access(Object rawId,Object rawGrant) {
        Stage stage=stages.stage(RequestContext.uuid(rawId));
        if(stage==null || !c.actorId.equals(stage.actorId())
                || !(rawGrant instanceof String grant) || !grant.equals(stage.grant()))throw unavailable();
        return stage;
    }

    private void authorize(Stage stage,Object rawGrant,String receivingRole) {
        if(!c.actorId.equals(stage.actorId()) || !Objects.equals(rawGrant,stage.grant()))
            throw unavailable();
        String scope=stage.scope();
        if(scope.equals("server"))return;
        boolean source=SOURCE.equals(stage.grantKind());
        String required=source?(Boolean.TRUE.equals(stage.history())?"owner":"viewer"):receivingRole;
        String current=effectiveRole(scope);
        if(current==null || repo.namespace(scope)==null || rank(current)<rank(required))throw unavailable();
    }

    private Stage readStage(Map<String,Object> request) {
        c.server.lockExchange(c.state.kernel,RequestContext.uuid(request.get("packageId")));
        Stage stage=access(request.get("packageId"),request.get("grant"));
        authorize(stage,request.get("grant"),"viewer");
        return stage;
    }

    private Map<String,Object> inspect(Map<String,Object> request)throws IOException {
        Stage stage=readStage(request);
        long now=now(), expires=stage.expiresAt();
        if(!"ready".equals(stage.state()) || now>=expires)throw unavailable();
        long deadline=now+READER;
        var envelope=ExchangePackageIndex.inspect(path(stage,ExchangePackageIndex.FILE));
        var result=new LinkedHashMap<>(envelope);
        var lease=leaseBinding(stage,deadline,UUID.randomUUID().toString());
        result.put("leaseId",CursorCodec.encode(c,lease));
        result.put("leaseExpiresAt",deadline);
        return result;
    }

    private Map<String,Object> leaseBinding(Stage stage,long deadline,String nonce) {
        return Json.map("type","exchange-lease","packageId",stage.id(),"actorId",c.actorId,
            "scope",stage.scope(),"grant",stage.grant(),"expires",deadline,"nonce",nonce);
    }

    private record ReadLease(Stage stage,String id,long deadline) {}

    private ReadLease lease(Map<String,Object> request) {
        Stage stage=readStage(request);
        String id=string(request.get("leaseId"));
        if(id.length()>2048)throw unavailable();
        Map<String,Object> decoded;
        try {decoded=CursorCodec.decode(c,id,now());}
        catch(RuntimeException failure){throw unavailable();}
        if(!(decoded.get("expires") instanceof Long deadline) || deadline<=now()
                || !(decoded.get("nonce") instanceof String nonce) || !leaseBinding(stage,deadline,nonce).equals(decoded)
                || !Set.of("ready","discarded").contains(stage.state()))throw unavailable();
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
        return Json.map("packageId",lease.stage().id(),"collection",collection,"entries",page.entries(),
            "nextCursor",page.nextPosition()==null?null:pageCursor(lease,collection,page.nextPosition()));
    }

    private Map<String,Object> pageCursor(ReadLease lease,String collection,long position) {
        var fields=Json.map("packageId",lease.stage().id(),"leaseId",lease.id(),"collection",collection,"position",position);
        String proof=CursorCodec.requestHash(c,Json.map("type","exchange-cursor","actorId",c.actorId,"cursor",fields));
        fields.put("integrity",proof);
        return fields;
    }

    private Map<String,Object> read(Map<String,Object> request)throws IOException {
        ReadLease lease=lease(request);
        String member=string(request.get("member"));
        Stage stage=lease.stage();
        long offset=integer(request.get("offset")), requested=integer(request.get("length")), size;
        if(offset<0 || requested<1 || requested>ResourceFiles.MAX_CHUNK)c.fail("VALIDATION_FAILED","Invalid Exchange read range");
        Path path;
        if(member.equals("package")) {
            size=stage.bytes();
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
        c.server.lockExchange(c.state.kernel,RequestContext.uuid(request.get("packageId")));
        Stage stage=access(request.get("packageId"),request.get("grant"));
        authorize(stage,request.get("grant"),"viewer");
        if("ready".equals(stage.state())) {
            stages.save(stage.cleanup("discarded",Math.min(stage.cleanupAfter(),now()+READER),
                stage.removeAfter(),stage.inputCleanup(),stage.cleaned()),unit("server"));
        }
        return Json.map("packageId",stage.id(),"state","discarded");
    }

    private String unit(String scope) {
        var owner=scope.equals("server")?repo.find("User","id",c.actorId):repo.namespace(scope);
        if(owner==null)throw unavailable();
        return c.server.prepareWorkflowCommit(c,scope,owner);
    }

    private static RuntimeException unavailable() {
        return ExceptionHandler.fail("NOT_FOUND","Exchange package or upload is unavailable");
    }
    private static RuntimeException malformed() {
        return ExceptionHandler.fail("VALIDATION_FAILED","Exchange input is malformed, incomplete or inconsistent");
    }
}
