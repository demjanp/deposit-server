package deposit.service;

import deposit.common.*;
import deposit.repository.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Direction;

/** Stage 7 exchange entry points. Export collection is read-only with respect to the scientific revision. */
public final class ExchangeService extends BaseService {
    private final ResourceRepository resources;
    private ExportRecords records;
    public ExchangeService(RequestContext c){super(c);resources=new ResourceRepository(c.tx,repo);}

    public Map<String,Object> execute(String procedure,Map<String,Object> request) {
        if(procedure.equals("deposit.load.data"))return validatePackage(request);
        try(var staged=new ExportRecords(resources.files.path("staging/exports"),()->c.state.kernel.assertOpen())){records=staged;return executeExport(procedure,request);}
        catch(IOException|UncheckedIOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to stage source snapshot");}
        finally{records=null;}
    }
    private Map<String,Object> executeExport(String procedure,Map<String,Object> request) {
        if(procedure.equals("deposit.load.data"))return validatePackage(request);
        if(procedure.equals("deposit.get.namespaceData")) {
            fields(request,"includeHistory");if(request.get("includeHistory")!=null&&!(request.get("includeHistory") instanceof Boolean))c.fail("VALIDATION_FAILED","includeHistory must be Boolean");scoped("viewer");boolean history=Boolean.TRUE.equals(request.get("includeHistory"));
            if(history)requireRole(c.namespaceId,"owner");
            return readReceipt(procedure,request,()->{var snapshot=snapshot(true);return completeSnapshot(ValueCodec.map(snapshot.get("manifest")),ValueCodec.map(snapshot.get("graph")),history,true);});
        }
        if(procedure.equals("deposit.get.data")) {
            if(request.get("rootIds")!=null&&!(request.get("rootIds") instanceof List<?>))c.fail("VALIDATION_FAILED","rootIds must be a list");
            List<?> rawRoots=request.get("rootIds") instanceof List<?> list?list:List.of();var rootSet=new TreeSet<String>();for(Object raw:rawRoots)rootSet.add(RequestContext.uuid(raw));List<?> roots=List.copyOf(rootSet);
            Map<String,Object> options=new LinkedHashMap<>(request);options.remove("rootIds");fields(options,"maxHops","rootClassBoundary","includeHistory");
            if(options.get("includeHistory")!=null&&!(options.get("includeHistory") instanceof Boolean))c.fail("VALIDATION_FAILED","includeHistory must be Boolean");scoped("viewer");boolean history=Boolean.TRUE.equals(options.get("includeHistory"));if(history)requireRole(c.namespaceId,"owner");
            long hops=integer(options.getOrDefault("maxHops",3L));if(hops<0||hops>10)c.fail("VALIDATION_FAILED","maxHops must be between zero and ten");
            Object rootBoundaryValue=options.getOrDefault("rootClassBoundary",true);if(!(rootBoundaryValue instanceof Boolean))c.fail("VALIDATION_FAILED","rootClassBoundary must be Boolean");boolean rootBoundary=(Boolean)rootBoundaryValue;
            boolean whole=roots.isEmpty();return readReceipt(procedure,request,()->{var full=snapshot(whole);var manifest=ValueCodec.map(full.get("manifest"));
                if(whole&&(options.containsKey("maxHops")&&hops!=3||options.containsKey("rootClassBoundary")&&!rootBoundary))c.fail("VALIDATION_FAILED","Whole-Namespace export does not accept subgraph traversal options");
                Map<String,Object> graph=ValueCodec.map(full.get("graph"));if(!whole){var filtered=subgraph(graph,roots,(int)hops,rootBoundary);graph=ValueCodec.map(filtered.get("graph"));manifest.put("rootIds",roots);manifest.put("maxHops",hops);manifest.put("rootClassBoundary",rootBoundary);manifest.put("boundaryReferences",filtered.get("boundaryReferences"));manifest.put("omittedEdgeCount",filtered.get("omittedEdgeCount"));}
                return completeSnapshot(manifest,graph,history,whole);});
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

    private Map<String,Object> snapshot(boolean wholeNamespace) {
        long pinned=repo.sequence(c.namespaceId);c.revision=pinned;
        Node ns=repo.namespace(c.namespaceId);
        var objects=data("Object");
        var classes=data("Class");var descriptors=data("Descriptor");
        Set<String> seen=Collections.newSetFromMap(records.<Boolean>index());
        try(var nodes=repo.scanNodes("Descriptor",Map.of("namespaceId",c.namespaceId))){while(nodes.hasNext()){Node descriptor=nodes.next();var edge=descriptor.getSingleRelationship(RelationshipType.withName("HAS_RESOURCE"),Direction.OUTGOING);if(edge!=null&&seen.add(CoreRepository.id(edge.getEndNode())))stage("resources",Json.map("kind","DResource","id",CoreRepository.id(edge.getEndNode())));}}
        var resourceRecords=records.list("resources");
        var graph=Json.map("classes",classes,"objects",objects,"descriptors",descriptors,
            "geometries",data("DGeometry"),"memberships",relationships("Membership"),"relationships",relationships("Relationship"),
            "classRelationships",relationships("ClassRelationship"),"patterns",data("Pattern"),
            "userTools",wholeNamespace?data("UserTool"):List.of(),"resources",resourceRecords);
        if(repo.sequence(c.namespaceId)!=pinned)c.fail("REVISION_CONFLICT","Namespace changed during export collection");
        var manifest=Json.map("packageId",UUID.randomUUID().toString(),"sourceNamespaceId",c.namespaceId,
            "name",ns.getProperty("name"),"description",ns.getProperty("description",""),"depositVersion",ns.getProperty("depositVersion",""),
            "exportedRevision",pinned,"createdAt",c.timestamp.toString(),"rootIds",List.of(),"scope",wholeNamespace?"namespace":"subgraph","files",Map.of());
        return Json.map("manifest",manifest,"graph",graph);
    }
    private HistoryProjection.Projection historyBundle(boolean wholeNamespace,Map<String,Object> graph){
        Set<String> objects=Collections.newSetFromMap(records.<Boolean>index()),patterns=Collections.newSetFromMap(records.<Boolean>index());
        for(Object row:(List<?>)graph.get("objects"))objects.add((String)ValueCodec.map(row).get("id"));
        for(Object row:(List<?>)graph.get("patterns"))patterns.add((String)ValueCodec.map(row).get("id"));
        return HistoryProjection.project(new HistoryService(c).historyExport(records),objects,patterns,wholeNamespace,records);
    }
    private void finalizeResources(Map<String,Object> graph,Set<String> historicalIds){
        Set<String> ids=Collections.newSetFromMap(records.<Boolean>index());ids.addAll(historicalIds);
        for(Object row:(List<?>)graph.get("resources"))ids.add((String)ValueCodec.map(row).get("id"));
        
        for(String id:ids){Node node=resources.resource(c.namespaceId,id);if(node==null)c.fail("RESOURCE_CORRUPT","Required historical resource is missing");
            resources.lock(id);ResourceStorage.Content verified=resources.verify(resources.location(id));
            stage("final-resources",Json.map("kind","DResource","id",id,"filename",node.getProperty("filename"),"bytes",verified.bytes(),"mediaType",node.getProperty("mediaType"),"sha256",verified.checksum(),"entry","resources/"+verified.checksum()));}
        graph.put("resources",records.list("final-resources"));
    }
    private Map<String,Object> completeSnapshot(Map<String,Object> manifest,Map<String,Object> graph,boolean includeHistory,boolean whole){
        var projection=includeHistory?historyBundle(whole,graph):null;
        finalizeResources(graph,projection==null?Set.of():projection.resourceIds());manifest.put("includeHistory",includeHistory);
        var result=Json.map("manifest",manifest,"graph",graph,"history",projection==null?null:projection.history());
        var bytes=new ByteArrayOutputStream();long[] count={0};
        try{Json.write(new OutputStream(){public void write(int value)throws IOException{if(++count[0]>1_048_576)throw new IOException("Inline snapshot requires staging");bytes.write(value);}public void write(byte[] value,int offset,int length)throws IOException{count[0]+=length;if(count[0]>1_048_576)throw new IOException("Inline snapshot requires staging");bytes.write(value,offset,length);}},result);return Json.read(bytes.toString(java.nio.charset.StandardCharsets.UTF_8));}
        catch(IllegalArgumentException failure){if(count[0]<=1_048_576)throw failure;return publishPackage(manifest,graph,projection==null?null:projection.history());}
    }
    private void stage(String category,Map<String,Object> value){try{c.state.kernel.assertOpen();records.add(category,value);}catch(IOException e){throw new UncheckedIOException(e);}}
    private List<Map<String,Object>> data(String label) {
        try(var nodes=repo.scanNodes(label,Map.of("namespaceId",c.namespaceId))){while(nodes.hasNext()){Node node=nodes.next();var value=repo.data(node,false);if(label.equals("Class")){int start=records.list("source-declarations").size();try(var declarations=repo.scanNodes("ClassDescriptor",Map.of("namespaceId",c.namespaceId,"ownerId",CoreRepository.id(node)))){while(declarations.hasNext())stage("source-declarations",repo.data(declarations.next(),false));}value.put("descriptors",records.slice("source-declarations",start,records.list("source-declarations").size()));}if(label.equals("Object"))value.remove("descriptors");if(label.equals("Descriptor"))value.put("value",ExchangePackageCodec.encodeValue(repo.descriptorType(node),value.get("value")));stage("record-"+label,value);}}
        return records.list("record-"+label);
    }
    private List<Map<String,Object>> relationships(String kind) {
        String label=kind.equals("ClassRelationship")?"Class":"Object";
        try(var nodes=repo.scanNodes(label,Map.of("namespaceId",c.namespaceId))){while(nodes.hasNext()){Node node=nodes.next();if(kind.equals("Membership")){var edge=repo.membership(node);if(edge!=null)stage("edge-"+kind,repo.data(edge));}
            else try(var edges=node.getRelationships(Direction.OUTGOING,RelationshipType.withName("RELATED_TO"))){for(var edge:edges){if(!kind.equals(CoreRepository.kind(edge)))throw new IllegalStateException("Invalid scoped relationship category");stage("edge-"+kind,repo.data(edge));}}}}
        return records.list("edge-"+kind);
    }
    private Set<String> identitySet(){return Collections.newSetFromMap(records.<Boolean>index());}
    private List<Map<String,Object>> selected(Map<String,Object> graph,String category,java.util.function.Predicate<Map<String,Object>> include){for(Object raw:(List<?>)graph.get(category)){var row=ValueCodec.map(raw);if(include.test(row))stage("selected-"+category,row);}return records.list("selected-"+category);}
    private Map<String,Object> subgraph(Map<String,Object> graph,List<?> rawRoots,int maxHops,boolean rootBoundary){
        Set<String> roots=identitySet();for(Object raw:rawRoots)roots.add(RequestContext.uuid(raw));if(roots.isEmpty())c.fail("VALIDATION_FAILED","Subgraph exports require roots");
        Map<String,Map<String,Object>> byId=records.index();for(Object raw:(List<?>)graph.get("objects")){var object=ValueCodec.map(raw);byId.put((String)object.get("id"),object);}for(String root:roots)if(!byId.containsKey(root))c.fail("NOT_FOUND","Root Object not found");
        Set<String> rootClasses=identitySet(),selected=identitySet();for(String root:roots){rootClasses.add((String)byId.get(root).get("classId"));selected.add(root);stage("traversal",Json.map("id",root,"depth",0L));}
        var queue=records.list("traversal");var edges=(List<?>)graph.get("relationships");
        for(int head=0;head<queue.size();head++){var current=queue.get(head);String id=(String)current.get("id");long depth=((Number)current.get("depth")).longValue();if(depth>=maxHops)continue;
            for(Object raw:edges){c.state.kernel.assertOpen();var edge=ValueCodec.map(raw);String source=(String)edge.get("sourceId"),target=(String)edge.get("targetId"),next=source.equals(id)?target:target.equals(id)?source:null;if(next==null)continue;
                if(rootBoundary&&rootClasses.contains(byId.get(source).get("classId"))&&Objects.equals(byId.get(source).get("classId"),byId.get(target).get("classId"))&&!roots.contains(source)&&!roots.contains(target))continue;
                if(selected.add(next))stage("traversal",Json.map("id",next,"depth",depth+1));
            }
        }
        Set<String> classIds=identitySet(),patternClassNames=identitySet(),patternIds=identitySet();for(String id:selected)classIds.add((String)byId.get(id).get("classId"));new PatternService(c).applicablePatterns(selected,patternIds);
        var includedPatterns=selected(graph,"patterns",row->patternIds.contains(row.get("id")));
        for(var pattern:includedPatterns)for(Object raw:ValueCodec.map(ValueCodec.map(pattern.get("definition")).get("objects")).values())patternClassNames.add((String)ValueCodec.map(raw).get("className"));
        var includedClasses=selected(graph,"classes",row->classIds.contains(row.get("id"))||patternClassNames.contains(row.get("name")));for(var clazz:includedClasses)classIds.add((String)clazz.get("id"));
        var objects=selected(graph,"objects",row->selected.contains(row.get("id")));var descriptors=selected(graph,"descriptors",row->selected.contains(row.get("objectId")));
        Set<String> descriptorIds=identitySet(),resourceIds=identitySet();for(var descriptor:descriptors){descriptorIds.add((String)descriptor.get("id"));if(descriptor.get("value") instanceof Map<?,?> value&&value.get("resourceId") instanceof String id)resourceIds.add(id);}
        var memberships=selected(graph,"memberships",row->selected.contains(row.get("objectId")));var geometries=selected(graph,"geometries",row->descriptorIds.contains(row.get("descriptorId")));
        var classEdges=selected(graph,"classRelationships",row->classIds.contains(row.get("sourceId"))&&classIds.contains(row.get("targetId")));var selectedEdges=selected(graph,"relationships",row->selected.contains(row.get("sourceId"))&&selected.contains(row.get("targetId")));
        Set<String> boundary=identitySet();long omitted=0;for(Object raw:edges){var edge=ValueCodec.map(raw);if(selected.contains(edge.get("sourceId"))!=selected.contains(edge.get("targetId"))){omitted++;String outside=selected.contains(edge.get("sourceId"))?(String)edge.get("targetId"):(String)edge.get("sourceId");if(boundary.add(outside))stage("boundary",Json.map("kind","Object","id",outside,"sourceNamespaceId",c.namespaceId));}}
        var includedResources=selected(graph,"resources",row->resourceIds.contains(row.get("id")));
        var result=Json.map("classes",includedClasses,"objects",objects,"descriptors",descriptors,"geometries",geometries,"memberships",memberships,"relationships",selectedEdges,"classRelationships",classEdges,"patterns",includedPatterns,"userTools",List.of(),"resources",includedResources);
        return Json.map("graph",result,"boundaryReferences",records.list("boundary"),"omittedEdgeCount",omitted);
    }
    private Map<String,Object> savePackage(Map<String,Object> request) {
        fields(request,"selection","includeHistory","maxHops","rootClassBoundary");Map<String,Object> options=new LinkedHashMap<>(request);options.remove("selection");
        Map<String,Object> selection=ValueCodec.map(request.get("selection"));fields(selection,"rootIds");if(selection.containsKey("rootIds")&&!(selection.get("rootIds") instanceof List<?>))c.fail("VALIDATION_FAILED","rootIds must be a list");List<?> rawRoots=selection.get("rootIds") instanceof List<?> list?list:null;List<?> roots=rawRoots==null?null:List.copyOf(rawRoots.stream().map(RequestContext::uuid).collect(java.util.stream.Collectors.toCollection(TreeSet::new)));
        if(options.get("includeHistory")!=null&&!(options.get("includeHistory") instanceof Boolean))c.fail("VALIDATION_FAILED","includeHistory must be Boolean");scoped("viewer");boolean history=Boolean.TRUE.equals(options.get("includeHistory"));if(history)requireRole(c.namespaceId,"owner");
        c.state.mutation=true;repo.lockActor(c.actorId);repo.lockNamespace(c.namespaceId);long revision=repo.sequence(c.namespaceId);c.revision=revision;
        String hash=CursorCodec.requestHash(c,Json.write(Json.map("procedure","deposit.save.data","request",request)));
        Node receipt=repo.receipt(c.namespaceId,c.operationId);
        if(receipt!=null){if(!c.actorId.equals(receipt.getProperty("actorId")))c.fail("FORBIDDEN","Operation belongs to another actor");if(!hash.equals(receipt.getProperty("requestHash")))c.fail("OPERATION_MISMATCH","Operation input changed");return repo.replay(receipt,c.namespaceId).data();}
        {
            boolean whole=roots==null||roots.isEmpty();var content=snapshot(whole);if(repo.sequence(c.namespaceId)!=revision)c.fail("REVISION_CONFLICT","Namespace changed during export collection");
            var snapshot=ValueCodec.map(content.get("manifest"));snapshot.put("exportedRevision",revision);var graphs=ValueCodec.map(content.get("graph"));
            if(whole&&(integer(options.getOrDefault("maxHops",3L))!=3||!Boolean.TRUE.equals(options.getOrDefault("rootClassBoundary",true))))c.fail("VALIDATION_FAILED","Whole-Namespace export does not accept subgraph traversal options");
            if(!whole){Object max=options.getOrDefault("maxHops",3L);long hops=integer(max);if(hops<0||hops>10)c.fail("LIMIT_EXCEEDED","maxHops must be between zero and ten");Object boundary=options.getOrDefault("rootClassBoundary",true);if(!(boundary instanceof Boolean))c.fail("VALIDATION_FAILED","rootClassBoundary must be Boolean");var filtered=subgraph(graphs,roots,(int)hops,(Boolean)boundary);graphs=ValueCodec.map(filtered.get("graph"));snapshot.put("scope","subgraph");snapshot.put("rootIds",roots);snapshot.put("maxHops",hops);snapshot.put("rootClassBoundary",boundary);snapshot.put("boundaryReferences",filtered.get("boundaryReferences"));snapshot.put("omittedEdgeCount",filtered.get("omittedEdgeCount"));}
            var projection=history?historyBundle(whole,graphs):null;snapshot.put("includeHistory",history);
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
            ExchangePackageCodec.ValidationSummary pkg=ExchangePackageCodec.validateSummary(stage,()->c.state.kernel.assertOpen());
            return Json.map("valid",true,"packageId",pkg.packageId(),"sourceNamespaceId",pkg.sourceNamespaceId(),"scope",pkg.scope(),"counts",pkg.counts());
        } catch(IOException|RuntimeException e){c.fail("VALIDATION_FAILED","Deposit package is malformed or incomplete");return Map.of();}
        finally {if(stage!=null)try{Files.deleteIfExists(stage);}catch(IOException ignored){}}
    }
    private Map<String,Object> counts(Map<String,Object> graph){var out=new LinkedHashMap<String,Object>();graph.forEach((k,v)->{if(v instanceof List<?> l)out.put(k,(long)l.size());});return out;}
}
