package deposit.service;

import deposit.common.*;
import deposit.repository.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.neo4j.graphdb.Node;

/** Pattern and portable-package exchange entry points. Export collection is read-only with respect to the scientific revision. */
public final class ExchangeService extends BaseService {
    private final ResourceRepository resources;
    private ExportRecords records;
    private ExchangeSnapshot assembly;
    public ExchangeService(RequestContext c){super(c);resources=new ResourceRepository(c.tx,repo);}

    public Map<String,Object> execute(String procedure,Map<String,Object> request) {
        if(procedure.equals("deposit.load.data"))return validatePackage(request);
        try(var staged=new ExportRecords(resources.files.path("staging/exports"),()->c.state.kernel.assertOpen())){records=staged;assembly=new ExchangeSnapshot(c,repo,staged);return executeExport(procedure,request);}
        catch(IOException|UncheckedIOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to stage source snapshot");}
        finally{records=null;assembly=null;}
    }
    private Map<String,Object> executeExport(String procedure,Map<String,Object> request) {
        if(procedure.equals("deposit.load.data"))return validatePackage(request);
        if(procedure.equals("deposit.get.namespaceData")) {
            fields(request,"includeHistory");if(request.get("includeHistory")!=null&&!(request.get("includeHistory") instanceof Boolean))c.fail("VALIDATION_FAILED","includeHistory must be Boolean");scoped("viewer");boolean history=Boolean.TRUE.equals(request.get("includeHistory"));
            Node ns=repo.namespace(c.namespaceId);boolean includeHistory="persistent".equals(ns.getProperty("historyMode","disabled"));
            if(includeHistory)requireRole(c.namespaceId,"owner");
            return readReceipt(procedure,request,()->{var snapshot=assembly.snapshot(true);return completeSnapshot(ValueCodec.map(snapshot.get("manifest")),ValueCodec.map(snapshot.get("graph")),includeHistory,true);});
        }
        if(procedure.equals("deposit.get.data")) {
            if(request.get("rootIds")!=null&&!(request.get("rootIds") instanceof List<?>))c.fail("VALIDATION_FAILED","rootIds must be a list");
            List<?> rawRoots=request.get("rootIds") instanceof List<?> list?list:List.of();var rootSet=new TreeSet<String>();for(Object raw:rawRoots)rootSet.add(RequestContext.uuid(raw));List<?> roots=List.copyOf(rootSet);
            Map<String,Object> options=new LinkedHashMap<>(request);options.remove("rootIds");fields(options,"maxHops","rootClassBoundary","includeHistory");
            if(options.get("includeHistory")!=null&&!(options.get("includeHistory") instanceof Boolean))c.fail("VALIDATION_FAILED","includeHistory must be Boolean");scoped("viewer");boolean history=Boolean.TRUE.equals(options.get("includeHistory"));
            long hops=integer(options.getOrDefault("maxHops",3L));if(hops<0||hops>10)c.fail("VALIDATION_FAILED","maxHops must be between zero and ten");
            Object rootBoundaryValue=options.getOrDefault("rootClassBoundary",true);if(!(rootBoundaryValue instanceof Boolean))c.fail("VALIDATION_FAILED","rootClassBoundary must be Boolean");boolean rootBoundary=(Boolean)rootBoundaryValue;
            boolean whole=roots.isEmpty();boolean includeHistory=whole&&"persistent".equals(repo.namespace(c.namespaceId).getProperty("historyMode","disabled"));if(includeHistory)requireRole(c.namespaceId,"owner");return readReceipt(procedure,request,()->{var full=assembly.snapshot(whole);var manifest=ValueCodec.map(full.get("manifest"));
                if(whole&&(options.containsKey("maxHops")&&hops!=3||options.containsKey("rootClassBoundary")&&!rootBoundary))c.fail("VALIDATION_FAILED","Whole-Namespace export does not accept subgraph traversal options");
                Map<String,Object> graph=ValueCodec.map(full.get("graph"));if(!whole){var filtered=assembly.subgraph(graph,roots,(int)hops,rootBoundary);graph=ValueCodec.map(filtered.get("graph"));manifest.put("rootIds",roots);manifest.put("maxHops",hops);manifest.put("rootClassBoundary",rootBoundary);manifest.put("boundaryReferences",filtered.get("boundaryReferences"));manifest.put("omittedEdgeCount",filtered.get("omittedEdgeCount"));}
                // History is a whole-Namespace capability. A subgraph package never carries it.
                return completeSnapshot(manifest,graph,includeHistory,whole);});
        }
        if(procedure.equals("deposit.save.data"))return savePackage(request);
        c.fail("VALIDATION_FAILED","Unsupported exchange operation");return Map.of();
    }

    private Map<String,Object> readReceipt(String procedure,Map<String,Object> request,java.util.function.Supplier<Map<String,Object>> collect){
        if(c.operationId==null||c.expectedRevision!=null)c.fail("VALIDATION_FAILED","Export operation requires an operation ID without expectedRevision");
        if(c.state.mutation)c.fail("VALIDATION_FAILED","One mutation per transaction");c.state.mutation=true;repo.lockActor(c.actorId);repo.lockNamespace(c.namespaceId);String hash=CursorCodec.requestHash(c,Json.write(Json.map("procedure",procedure,"request",request)));
        Node prior=repo.receipt(c.namespaceId,c.operationId);if(prior!=null){c.replayed=true;if(!c.actorId.equals(prior.getProperty("actorId")))c.fail("FORBIDDEN","Operation belongs to another actor");if(!hash.equals(prior.getProperty("requestHash")))c.fail("OPERATION_MISMATCH","Operation input changed");return repo.replay(prior,c.namespaceId).data();}
        Map<String,Object> data=collect.get();repo.saveReceipt(c,c.namespaceId,hash,new Results.Outcome(null,null,data));return data;
    }

    private void finalizeResources(Map<String,Object> graph,Set<String> historicalIds){
        Set<String> ids=Collections.newSetFromMap(records.<Boolean>index());ids.addAll(historicalIds);
        for(Object row:(List<?>)graph.get("resources"))ids.add((String)ValueCodec.map(row).get("id"));
        
        for(String id:ids){Node node=resources.resource(c.namespaceId,id);if(node==null)c.fail("RESOURCE_CORRUPT","Required historical resource is missing");
            resources.lock(id);ResourceStorage.Content verified=resources.verify(resources.location(id));
            stage("final-resources",PortableImportRecords.resource(id,(String)node.getProperty("filename"),verified.bytes(),(String)node.getProperty("mediaType"),verified.checksum()));}
        graph.put("resources",records.list("final-resources"));
    }
    private void stage(String category,Map<String,Object> value){try{c.state.kernel.assertOpen();records.add(category,value);}catch(IOException e){throw new UncheckedIOException(e);}}
    private Map<String,Object> completeSnapshot(Map<String,Object> manifest,Map<String,Object> graph,boolean includeHistory,boolean whole){
        includeHistory=whole&&"persistent".equals(manifest.get("historyMode"));
        var projection=includeHistory?assembly.historyBundle(whole,graph):null;
        finalizeResources(graph,projection==null?Set.of():projection.resourceIds());manifest.put("includeHistory",includeHistory);
        var result=Json.map("manifest",manifest,"graph",graph,"history",projection==null?null:projection.history());
        var bytes=new ByteArrayOutputStream();long[] count={0};
        try{Json.write(new OutputStream(){public void write(int value)throws IOException{if(++count[0]>1_048_576)throw new IOException("Inline snapshot requires staging");bytes.write(value);}public void write(byte[] value,int offset,int length)throws IOException{count[0]+=length;if(count[0]>1_048_576)throw new IOException("Inline snapshot requires staging");bytes.write(value,offset,length);}},result);return Json.read(bytes.toString(java.nio.charset.StandardCharsets.UTF_8));}
        catch(IllegalArgumentException failure){if(count[0]<=1_048_576)throw failure;return publishPackage(manifest,graph,projection==null?null:projection.history());}
    }
    private Map<String,Object> savePackage(Map<String,Object> request) {
        fields(request,"selection","includeHistory","maxHops","rootClassBoundary");Map<String,Object> options=new LinkedHashMap<>(request);options.remove("selection");
        Map<String,Object> selection=ValueCodec.map(request.get("selection"));fields(selection,"rootIds");if(selection.containsKey("rootIds")&&!(selection.get("rootIds") instanceof List<?>))c.fail("VALIDATION_FAILED","rootIds must be a list");List<?> rawRoots=selection.get("rootIds") instanceof List<?> list?list:null;List<?> roots=rawRoots==null?null:List.copyOf(rawRoots.stream().map(RequestContext::uuid).collect(java.util.stream.Collectors.toCollection(TreeSet::new)));
        if(options.get("includeHistory")!=null&&!(options.get("includeHistory") instanceof Boolean))c.fail("VALIDATION_FAILED","includeHistory must be Boolean");scoped("viewer");
        c.state.mutation=true;repo.lockActor(c.actorId);repo.lockNamespace(c.namespaceId);long revision=repo.sequence(c.namespaceId);c.revision=revision;
        String hash=CursorCodec.requestHash(c,Json.write(Json.map("procedure","deposit.save.data","request",request)));
        Node receipt=repo.receipt(c.namespaceId,c.operationId);
        if(receipt!=null){if(!c.actorId.equals(receipt.getProperty("actorId")))c.fail("FORBIDDEN","Operation belongs to another actor");if(!hash.equals(receipt.getProperty("requestHash")))c.fail("OPERATION_MISMATCH","Operation input changed");return repo.replay(receipt,c.namespaceId).data();}
        {
            boolean whole=roots==null||roots.isEmpty();var content=assembly.snapshot(whole);if(repo.sequence(c.namespaceId)!=revision)c.fail("REVISION_CONFLICT","Namespace changed during export collection");
            var snapshot=ValueCodec.map(content.get("manifest"));snapshot.put("exportedRevision",revision);var graphs=ValueCodec.map(content.get("graph"));
            if(whole&&(integer(options.getOrDefault("maxHops",3L))!=3||!Boolean.TRUE.equals(options.getOrDefault("rootClassBoundary",true))))c.fail("VALIDATION_FAILED","Whole-Namespace export does not accept subgraph traversal options");
            if(!whole){Object max=options.getOrDefault("maxHops",3L);long hops=integer(max);if(hops<0||hops>10)c.fail("LIMIT_EXCEEDED","maxHops must be between zero and ten");Object boundary=options.getOrDefault("rootClassBoundary",true);if(!(boundary instanceof Boolean))c.fail("VALIDATION_FAILED","rootClassBoundary must be Boolean");var filtered=assembly.subgraph(graphs,roots,(int)hops,(Boolean)boundary);graphs=ValueCodec.map(filtered.get("graph"));snapshot.put("scope","subgraph");snapshot.put("rootIds",roots);snapshot.put("maxHops",hops);snapshot.put("rootClassBoundary",boundary);snapshot.put("boundaryReferences",filtered.get("boundaryReferences"));snapshot.put("omittedEdgeCount",filtered.get("omittedEdgeCount"));}
            boolean history=whole&&"persistent".equals(snapshot.get("historyMode"));if(history)requireRole(c.namespaceId,"owner");var projection=history?assembly.historyBundle(true,graphs):null;snapshot.put("includeHistory",history);
            finalizeResources(graphs,projection==null?Set.of():projection.resourceIds());
            var result=publishPackage(snapshot,graphs,projection==null?null:projection.history());repo.saveReceipt(c,c.namespaceId,hash,new Results.Outcome(null,null,result));return result;
        }
    }
    private Map<String,Object> publishPackage(Map<String,Object> snapshot,Map<String,Object> graphs,Map<String,Object> historyData){
        Path stage=null;Path blobDirectory=null;
        try{
            stage=resources.files.path("staging/exports/"+UUID.randomUUID()+".zip");Files.createDirectories(stage.getParent());var blobs=new TreeMap<String,Path>();
            blobDirectory=Files.createTempDirectory(stage.getParent(),"resource-content-");long expanded=0;
            for(Object raw:(List<?>)graphs.get("resources")){
                c.state.kernel.assertOpen();String resourceId=(String)ValueCodec.map(raw).get("id");Node loc=resources.location(resourceId);ResourceStorage.Content verified=resources.verify(loc);
                if(blobs.containsKey(verified.checksum()))continue;
                if(blobs.size()+3+(historyData==null?0:1)>ExchangePackageCodec.MAX_ENTRIES||verified.bytes()>ExchangePackageCodec.MAX_EXPANDED-expanded)c.fail("LIMIT_EXCEEDED","Package resource bounds exceeded");expanded+=verified.bytes();
                Path blob=blobDirectory.resolve(verified.checksum());try(InputStream in=resources.storage.open(verified,0,verified.bytes());OutputStream out=Files.newOutputStream(blob,StandardOpenOption.CREATE_NEW)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){c.state.kernel.assertOpen();out.write(buffer,0,n);}}
                if(Files.size(blob)!=verified.bytes()||!verified.checksum().equals(ResourceFiles.checksum(blob)))c.fail("RESOURCE_CORRUPT","Source resource changed during export");blobs.put(verified.checksum(),blob);
            }
            ExchangePackageCodec.write(stage,snapshot,graphs,historyData,blobs);ExchangePackageCodec.validateSummary(stage,()->c.state.kernel.assertOpen());if(repo.sequence(c.namespaceId)!=c.revision)c.fail("REVISION_CONFLICT","Source revision changed during export finalization");
            long bytes=Files.size(stage);if(bytes>ResourceFiles.MAX_BYTES)c.fail("LIMIT_EXCEEDED","Generated package exceeds resource limit");String checksum=ResourceFiles.checksum(stage),id=UUID.randomUUID().toString(),stored=ResourceFiles.filename("deposit-package.zip",checksum);
            var contentRef=new ResourceStorage.Content(id,stored,bytes,checksum);resources.lock(id);resources.storage.publish(contentRef,stage);resources.storage.verify(contentRef);
            resources.record("DepositResourceLocation",id,Json.map("namespaceId",c.namespaceId,"storedFilename",stored,"bytes",bytes,"checksum",checksum,"leaseUntil",resources.now()+ResourceFiles.LEASE,"everAttached",false,"published",true));
            resources.record("DResource",id,Json.map("namespaceId",c.namespaceId,"filename","deposit-package.zip","storedFilename",stored,"mediaType","application/vnd.deposit.package+zip","bytes",bytes,"checksum",checksum,"state","ready","createdBy",c.actorId,"updatedBy",c.actorId,"createdAt",c.timestamp,"updatedAt",c.timestamp));
            resources.record("DepositExportGrant",UUID.randomUUID().toString(),Json.map("resourceId",id,"namespaceId",c.namespaceId,"actorId",c.actorId,"history",historyData!=null,"expires",resources.now()+ResourceFiles.LEASE));
            return Json.map("resourceId",id,"checksum",checksum,"bytes",bytes,"state","ready");
        }catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to finalize exchange package");}
        finally{if(blobDirectory!=null)try(var paths=Files.walk(blobDirectory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}catch(IOException ignored){}if(stage!=null)try{Files.deleteIfExists(stage);}catch(IOException ignored){}}
    }
    private Map<String,Object> validatePackage(Map<String,Object> request) {
        fields(request,"resourceId");readContext();scoped("editor");String id=RequestContext.uuid(request.get("resourceId"));Node resource=resources.resource(c.namespaceId,id);
        if(resource==null||!resources.accessible(resource,c.actorId,effectiveRole(c.namespaceId),false))c.fail("NOT_FOUND","Resource not found");
        Node location=resources.location(id);resources.lock(id);ResourceStorage.Content content=resources.verify(location);
        Path stage=null;
        try {
            stage=resources.files.path("staging/validate/"+UUID.randomUUID()+".zip");Files.createDirectories(stage.getParent());
            try(InputStream in=resources.storage.open(content,0,content.bytes());OutputStream out=Files.newOutputStream(stage,StandardOpenOption.CREATE_NEW)){in.transferTo(out);}
            catch(IOException e){c.fail("VALIDATION_FAILED","Deposit package is malformed or incomplete");return Map.of();}
            ExchangePackageCodec.ValidationSummary pkg;
            try{pkg=ExchangePackageCodec.validateSummary(stage,()->c.state.kernel.assertOpen());}
            catch(IOException e){c.fail("VALIDATION_FAILED","Deposit package is malformed or incomplete");return Map.of();}
            return Json.map("valid",true,"packageId",pkg.packageId(),"sourceNamespaceId",pkg.sourceNamespaceId(),"scope",pkg.scope(),"counts",pkg.counts());
        } catch(IOException e){c.fail("VALIDATION_FAILED","Deposit package is malformed or incomplete");return Map.of();}
        finally {if(stage!=null)try{Files.deleteIfExists(stage);}catch(IOException ignored){}}
    }
    private Map<String,Object> counts(Map<String,Object> graph){var out=new LinkedHashMap<String,Object>();graph.forEach((k,v)->{if(v instanceof List<?> l)out.put(k,(long)l.size());});return out;}
}
