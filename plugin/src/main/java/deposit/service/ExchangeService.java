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
    private StorageAdmission.Reservation admission;
    private ExportRecords.StorageBudget exportBudget;
    private ExportRecords records;
    private ExchangeSnapshot assembly;
    private Path exportWorkspace;
    public ExchangeService(RequestContext c){super(c);resources=new ResourceRepository(c.tx,repo);}

    public Map<String,Object> execute(String procedure,Map<String,Object> request) {
        return executeExport(procedure,request);
    }
    static void validateSelector(Object value,Object rawOptions) {
        if(!(rawOptions instanceof Map<?,?>))throw ExceptionHandler.fail("VALIDATION_FAILED","Export options must be a map");
        var options=ValueCodec.map(rawOptions);
        fields(options,"maxHops","rootClassBoundary","includeHistory");
        long hops=integer(options.getOrDefault("maxHops",3L));
        Object boundary=options.getOrDefault("rootClassBoundary",true);
        if(hops<0||hops>10||!(boundary instanceof Boolean)
                ||options.containsKey("includeHistory")&&!(options.get("includeHistory") instanceof Boolean))
            throw ExceptionHandler.fail("VALIDATION_FAILED","Invalid export options");
        if(value==null) {
            if(hops!=3||!Boolean.TRUE.equals(boundary))
                throw ExceptionHandler.fail("VALIDATION_FAILED","Whole-Namespace export does not accept subgraph traversal options");
            return;
        }
        if(!(value instanceof List<?> roots)||roots.isEmpty())
            throw ExceptionHandler.fail("VALIDATION_FAILED","Explicit export roots must be a nonempty list");
        // A UUID contributes at most 39 encoded bytes. Leave room for the two
        // request envelopes, operation identity and constant-size traversal options.
        if(roots.size()>(1_048_576-1024)/39)
            throw ExceptionHandler.fail("LIMIT_EXCEEDED","Explicit export selector exceeds request budget");
        for(Object root:roots)RequestContext.uuid(root);
        if(Boolean.TRUE.equals(options.get("includeHistory")))
            throw ExceptionHandler.fail("VALIDATION_FAILED","Only a whole-Namespace export can include history");
    }
    private Map<String,Object> executeExport(String procedure,Map<String,Object> request) {
        if(procedure.equals("deposit.get.namespaceData")) {
            fields(request,"includeHistory");if(request.get("includeHistory")!=null&&!(request.get("includeHistory") instanceof Boolean))c.fail("VALIDATION_FAILED","includeHistory must be Boolean");scoped("viewer");boolean history=!Boolean.FALSE.equals(request.get("includeHistory"));
            Node ns=repo.namespace(c.namespaceId);boolean includeHistory=history&&"persistent".equals(ns.getProperty("historyMode","disabled"));
            if(includeHistory)requireRole(c.namespaceId,"owner");
            return collectSnapshot(procedure,request,()->{var snapshot=assembly.snapshot(true);return completeSnapshot(ValueCodec.map(snapshot.get("manifest")),ValueCodec.map(snapshot.get("graph")),includeHistory,true);});
        }
        if(procedure.equals("deposit.get.data")||procedure.equals("deposit.exchange.export")) {
            boolean canonical=procedure.equals("deposit.exchange.export");
            Object rawRoots=request.get("rootIds");
            var options=canonical?ValueCodec.map(request.get("options")):new LinkedHashMap<>(request);
            if(!canonical)options.remove("rootIds");
            validateSelector(rawRoots,options);
            boolean whole=rawRoots==null;
            var rootSet=new TreeSet<String>();
            if(rawRoots instanceof List<?> roots)for(Object root:roots)rootSet.add(RequestContext.uuid(root));
            var roots=List.copyOf(rootSet);
            long hops=integer(options.getOrDefault("maxHops",3L));
            boolean rootBoundary=(boolean)options.getOrDefault("rootClassBoundary",true);
            scoped("viewer");
            boolean history=whole&&(canonical||!Boolean.FALSE.equals(options.get("includeHistory")))
                &&"persistent".equals(repo.namespace(c.namespaceId).getProperty("historyMode","disabled"));
            if(history)requireRole(c.namespaceId,"owner");
            return collectSnapshot(procedure,request,()->{
                var full=assembly.snapshot(whole);
                var manifest=ValueCodec.map(full.get("manifest"));
                var graph=ValueCodec.map(full.get("graph"));
                if(!whole) {
                    var filtered=assembly.subgraph(graph,roots,(int)hops,rootBoundary);
                    graph=ValueCodec.map(filtered.get("graph"));
                    manifest.put("rootIds",roots);
                    manifest.put("maxHops",hops);
                    manifest.put("rootClassBoundary",rootBoundary);
                    manifest.put("boundaryReferences",filtered.get("boundaryReferences"));
                    manifest.put("omittedEdgeCount",filtered.get("omittedEdgeCount"));
                }
                if(!canonical)return completeSnapshot(manifest,graph,history,whole);
                var projection=history?assembly.historyBundle(true,graph):null;
                finalizeResources(graph,projection==null?Set.of():projection.resourceIds());
                return publishPackage(manifest,graph,projection==null?null:projection.history());
            });
        }
        c.fail("VALIDATION_FAILED","Unsupported exchange operation");return Map.of();
    }

    private Map<String,Object> collectSnapshot(String procedure,Map<String,Object> request,java.util.function.Supplier<Map<String,Object>> collect){
        if(c.operationId==null||c.expectedRevision!=null)c.fail("VALIDATION_FAILED","Export operation requires an operation ID without expectedRevision");
        if(c.state.mutation)c.fail("VALIDATION_FAILED","One mutation per transaction");c.state.mutation=true;repo.lockActor(c.actorId);repo.lockNamespace(c.namespaceId);String hash=c.state.operationClaimed?c.inputHash:CursorCodec.requestHash(c,Json.write(Json.map("procedure",procedure,"request",request)));
        c.revision=repo.sequence(c.namespaceId);
        Map<String,Object> data=withExportAdmission(collect);
        if(!c.state.operationClaimed)repo.saveReceipt(c,c.namespaceId,hash,new Results.Outcome(null,null,data));
        return data;
    }

    private void finalizeResources(Map<String,Object> graph,Set<String> historicalIds){
        Set<String> ids=Collections.newSetFromMap(records.<Boolean>index());ids.addAll(historicalIds);
        for(Object row:(List<?>)graph.get("resources"))ids.add((String)ValueCodec.map(row).get("id"));
        
        for(String id:ids){Node node=resources.resource(c.namespaceId,id);if(node==null)c.fail("RESOURCE_CORRUPT","Required historical resource is missing");
            resources.lock(id);ResourceStorage.Content verified=resources.verifyCompleted(node);
            stage("final-resources",PortableImportRecords.resource(id,(String)node.getProperty("filename"),verified.bytes(),(String)node.getProperty("mediaType"),verified.checksum()));}
        graph.put("resources",records.list("final-resources"));
    }
    private void stage(String category,Map<String,Object> value){try{c.state.kernel.assertOpen();records.add(category,value);}catch(IOException e){throw new UncheckedIOException(e);}}
    private Map<String,Object> completeSnapshot(Map<String,Object> manifest,Map<String,Object> graph,boolean includeHistory,boolean whole){
        if(whole&&!includeHistory&&"persistent".equals(manifest.get("historyMode")))
            manifest.put("historyMode","disabled");
        var projection=includeHistory?assembly.historyBundle(whole,graph):null;
        finalizeResources(graph,projection==null?Set.of():projection.resourceIds());manifest.put("includeHistory",includeHistory);
        var result=Json.map("manifest",manifest,"graph",graph,"history",projection==null?null:projection.history());
        var bytes=new ByteArrayOutputStream();long[] count={0};
        try{Json.write(new OutputStream(){public void write(int value)throws IOException{if(++count[0]>1_048_576)throw new IOException("Inline snapshot requires staging");bytes.write(value);}public void write(byte[] value,int offset,int length)throws IOException{count[0]+=length;if(count[0]>1_048_576)throw new IOException("Inline snapshot requires staging");bytes.write(value,offset,length);}},result);return Json.read(bytes.toString(java.nio.charset.StandardCharsets.UTF_8));}
        catch(IllegalArgumentException failure){if(count[0]<=1_048_576)throw failure;return publishPackage(manifest,graph,projection==null?null:projection.history());}
    }
    private Map<String,Object> publishPackage(Map<String,Object> snapshot,Map<String,Object> graphs,Map<String,Object> historyData){
        try{
            var hashes=records.<Boolean>index();
            Path blobDirectory=Files.createTempDirectory(exportWorkspace,"resource-content-");
            for(Object raw:(List<?>)graphs.get("resources")){
                c.state.kernel.assertOpen();String resourceId=(String)ValueCodec.map(raw).get("id");Node resource=resources.resource(c.namespaceId,resourceId);ResourceStorage.Content verified=resources.verifyCompleted(resource);
                if(hashes.put(verified.checksum(),true)!=null)continue;
                Path blob=blobDirectory.resolve(verified.checksum());c.server.storage.admission.grow(admission,Map.of(resources.files.root,verified.bytes()));try(InputStream in=resources.storage.open(verified,0,verified.bytes());OutputStream out=Files.newOutputStream(blob,StandardOpenOption.CREATE_NEW)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){c.state.kernel.assertOpen();out.write(buffer,0,n);}}c.server.storage.admission.materialized(admission,resources.files.root,verified.bytes());
                if(Files.size(blob)!=verified.bytes()||!verified.checksum().equals(ResourceFiles.checksum(blob)))c.fail("RESOURCE_CORRUPT","Source resource changed during export");
            }
            Map<String,Path> blobs=new AbstractMap<>() {
                @Override public Set<Entry<String,Path>> entrySet() {
                    return new AbstractSet<>() {
                        public int size(){return hashes.size();}
                        public Iterator<Entry<String,Path>> iterator() {
                            return hashes.keySet().stream().<Entry<String,Path>>map(hash->Map.entry(hash,blobDirectory.resolve(hash))).iterator();
                        }
                    };
                }
            };
            var packages=new ExchangePackages(c);
            var output=packages.createExport();
            ExchangePackageCodec.write(packages.archive(output),snapshot,graphs,historyData,blobs,exportBudget,()->c.state.kernel.assertOpen());
            var reference=packages.publish(output,ExchangePackages.SOURCE,historyData!=null,exportBudget);
            if(repo.sequence(c.namespaceId)!=c.revision)c.fail("REVISION_CONFLICT","Source revision changed during export finalization");
            reference.remove("memberCount");
            return reference;
        }catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to finalize exchange package");}

    }
    private <T>T withExportAdmission(java.util.function.Supplier<T> work){
        String workspaceId=UUID.randomUUID().toString(), relative="staging/exports/attempt-"+workspaceId;
        try{
            exportWorkspace=resources.files.path(relative);
            admission=c.server.storage.admission.acquire("export-scratch",workspaceId,c.operationId,"exchange-export",
                Map.of(exportWorkspace,64L*1024),Json.map("phase","export-scratch","actorId",c.actorId,"receiptScope",c.namespaceId));
            Files.createDirectories(exportWorkspace);
            exportBudget=new AdmissionBudget();
            try(var staged=new ExportRecords(exportWorkspace,()->c.state.kernel.assertOpen(),exportBudget)){
                records=staged;assembly=new ExchangeSnapshot(c,repo,staged);return work.get();
            }
        }
        catch(StorageAdmission.StoragePressureException pressure){throw ExceptionHandler.fail("LIMIT_EXCEEDED",pressure.getMessage());}
        catch(IOException|UncheckedIOException failure){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to stage source snapshot");}
        finally{
            records=null;assembly=null;
            if(exportBudget instanceof AdmissionBudget budget)try{budget.finish();}catch(IOException failure){c.server.storage.diagnostic="Exchange storage admission settlement pending";}
            exportBudget=null;
            if(admission!=null)try{resources.files.deleteOwnedStagingTree(relative);c.server.storage.admission.close(admission);}
                catch(IOException|StorageAdmission.StoragePressureException failure){c.server.storage.diagnostic="Exchange storage admission cleanup pending";}
            admission=null;exportWorkspace=null;
        }
    }
    private final class AdmissionBudget implements ExportRecords.StorageBudget {
        private static final long BLOCK=64L*1024,SETTLE=64L*1024;
        private long outstanding,pending;
        public void reserve(long bytes)throws IOException{if(bytes<0)throw new IOException("Negative export staging size");if(outstanding<bytes){long addition=Math.max(BLOCK,bytes-outstanding);c.server.storage.admission.grow(admission,Map.of(resources.files.root,addition));outstanding=Math.addExact(outstanding,addition);}}
        public void materialized(long bytes)throws IOException{if(bytes<0||bytes>outstanding)throw new IOException("Export staging write exceeded its reservation");outstanding-=bytes;pending=Math.addExact(pending,bytes);if(pending>=SETTLE)settle();}
        private void settle()throws IOException{if(pending>0){c.server.storage.admission.materialized(admission,resources.files.root,pending);pending=0;}}
        void finish()throws IOException{settle();}
    }
}
