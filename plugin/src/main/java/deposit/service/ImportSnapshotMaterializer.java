package deposit.service;

import deposit.common.*;
import deposit.repository.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.neo4j.graphdb.Node;

/** Applies one validated snapshot using the caller's transaction and resource-import capability. */
final class ImportSnapshotMaterializer {
    private final RequestContext c;
    private final CoreRepository repo;
    private final ResourceRepository resources;

    ImportSnapshotMaterializer(RequestContext c,CoreRepository repo,ResourceRepository resources){this.c=c;this.repo=repo;this.resources=resources;}

    Map<String,Object> apply(ExchangePackageCodec.PackageData pkg,Path archive,String namespace,Map<String,Object> classMap,Map<String,Object> objectMappings,Map<String,Object> rules){applyFormScopes(pkg.graph(),namespace,objectMappings,rules);return materialize(pkg,archive,namespace,classMap,objectMappings);}

    private void applyFormScopes(Map<String,Object> graph,String namespace,Map<String,Object> mappings,Map<String,Object> rules){
        Object raw=rules.get("_formRootSource");if(!(raw instanceof String source))return;
        Node root=repo.entity("Object",namespace,(String)mappings.get(source));if(root==null)c.fail("PLAN_STALE","Form root is no longer available");
        ImportWriteBudget budget=ImportWriteBudget.forRequest(c);
        for(Object field:(List<?>)rules.get("_formRootDescriptors")){Node d=repo.descriptor(root,(String)field);if(d!=null){reserveRemoval(d,budget);repo.remove(d,new ArrayList<>());}}
        var remove=new ArrayList<org.neo4j.graphdb.Relationship>();
        try(var edges=root.getRelationships(org.neo4j.graphdb.Direction.BOTH,org.neo4j.graphdb.RelationshipType.withName("RELATED_TO"))){for(var edge:edges)for(Object scopeRaw:(List<?>)rules.get("_formRootRelationships")){var scope=ValueCodec.map(scopeRaw);boolean outgoing="outgoing".equals(scope.get("direction"));if(!(outgoing?edge.getStartNode():edge.getEndNode()).equals(root))continue;Node other=outgoing?edge.getEndNode():edge.getStartNode();if(Objects.equals(scope.get("label"),edge.getProperty("label"))&&Objects.equals(scope.get("className"),repo.objectClass(other).getProperty("name"))){remove.add(edge);break;}}}
        for(var edge:remove){budget.reserveBeforeMutation(edge);repo.remove(edge,new ArrayList<>());}
    }

    private void reserveRemoval(Node node,ImportWriteBudget budget){
        budget.reserveBeforeMutation(node);var geometry=new GeometryRepository(c.tx,repo).geometry(node);
        if(geometry!=null)budget.reserveBeforeMutation(geometry);
        try(var edges=node.getRelationships()){for(var edge:edges)budget.reserveBeforeMutation(edge);}
        if(geometry!=null)try(var edges=geometry.getRelationships()){for(var edge:edges)budget.reserveBeforeMutation(edge);}
    }

Map<String,Object> materialize(ExchangePackageCodec.PackageData pkg,Path archive,String namespace,Map<String,Object> classMap,Map<String,Object> objectMappings){
    Map<String,Object> graph=pkg.graph();ImportScratch mappings;
    var scratchBudget=ImportWriteBudget.forRequest(c).scratchLease();
    try{mappings=new ImportScratch(resources.files.path("staging/imports"),()->c.state.kernel.assertOpen(),scratchBudget::reserve,scratchBudget::close);}
    catch(IOException failure){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to create private import mapping index");}
    c.state.release.add(()->{try{mappings.close();}catch(IOException failure){c.server.storage.diagnostic="Private import mapping cleanup pending";}});
    Set<String> currentResourceIds=currentDescriptorResourceIds(graph,mappings);long stagedBytes=0;
    for(Object raw:(List<?>)graph.get("resources")){var resource=ValueCodec.map(raw);if(currentResourceIds.contains(resource.get("id")))try{stagedBytes=Math.addExact(stagedBytes,((Number)resource.get("bytes")).longValue());}catch(ArithmeticException overflow){c.fail("LIMIT_EXCEEDED","Import resource size is not representable");}}
    StorageAdmission.Reservation admission;try{admission=c.server.storage.admission.acquire("import-apply",ImportWriteBudget.reservationOwnerId(c.namespaceId,c.actorId,c.operationId),c.operationId,"import-resource-materialization",Map.of(resources.files.root,stagedBytes),Json.map("phase","resource-expansion","namespaceId",namespace,"receiptScope",c.namespaceId,"actorId",c.actorId,"operationId",c.operationId,"expectedBytes",stagedBytes));}catch(StorageAdmission.StoragePressureException pressure){c.fail("LIMIT_EXCEEDED",pressure.getMessage());return Map.of();}catch(IOException failure){c.fail("RESOURCE_CORRUPT","Unable to prepare import storage admission");return Map.of();}
    boolean[] committed={false};ResourceRepository.ImportedResources[] importedOwner={null};
    c.state.afterCommit.add(()->committed[0]=true);
    // Install cleanup ownership before database admission or any other fallible work.
    // If no ImportedResources capability is created, no publication could have started,
    // so rollback may safely release this reservation.
    c.state.release.add(()->{var imported=importedOwner[0];if(imported!=null&&(imported.publicationFailed()||imported.publicationStarted()&&!committed[0])){c.server.storage.diagnostic="Uncommitted import publication retained for startup orphan reconciliation";return;}try{c.server.storage.admission.close(admission);}catch(IOException failure){c.server.storage.diagnostic="Import storage admission cleanup pending";}});
    ImportWriteBudget writeBudget=ImportWriteBudget.forRequest(c);writeBudget.reserveGraph(graph,currentResourceIds);
    var imported=resources.beginImport(namespace,c.actorId,admission);importedOwner[0]=imported;
    try(imported){
    Map<String,String> classes=mappings.orderedMap(),objects=mappings.orderedMap(),resourcesMap=mappings.orderedMap(),geometriesMap=mappings.orderedMap(),descriptors=mappings.orderedMap(),memberships=mappings.orderedMap(),relationships=mappings.orderedMap(),classRelationships=mappings.orderedMap(),patterns=mappings.orderedMap(),userTools=mappings.orderedMap(),mapping=mappings.orderedMap();long createdObjects=0;
    for(Object raw:(List<?>)graph.get("classes")){var source=ValueCodec.map(raw);String sourceId=(String)source.get("id"),targetName=BaseService.name(classMap.getOrDefault(source.get("name"),(String)source.get("name")));Node target=repo.find("Class",Map.of("namespaceId",namespace,"name",targetName));
        if(target==null)target=repo.create("Class",namespace,c.actorId,Json.map("name",targetName,"position",source.get("position"),"order",source.getOrDefault("order",0L),"comment",source.getOrDefault("comment","")));
        for(Object rd:(List<?>)source.get("descriptors")){var d=ValueCodec.map(rd);String field=(String)d.get("name"),type=(String)d.get("type");Node existing=repo.declaration(target,field);if(existing==null)repo.attach("ClassDescriptor",target,c.actorId,Json.map("name",field,"type",type,"order",d.getOrDefault("order",0L),"comment",d.getOrDefault("comment","")));else if(!type.equals(existing.getProperty("type")))c.fail("IMPORT_CONFLICT","Class declaration changed after analysis");}
        String targetId=CoreRepository.id(target);classes.put(sourceId,targetId);mapping.put(sourceId,targetId);
    }
    for(Object raw:(List<?>)graph.get("resources")){var source=ValueCodec.map(raw);String sourceId=(String)source.get("id");if(!currentResourceIds.contains(sourceId))continue;String checksum=(String)source.get("sha256");Path staged=null;try(ZipFile zip=new ZipFile(archive.toFile())){var entry=zip.getEntry("resources/"+checksum);if(entry==null)c.fail("RESOURCE_CORRUPT","Package resource content is missing");staged=resources.files.path("staging/imports/content-"+UUID.randomUUID());Files.createDirectories(staged.getParent());long expected=((Number)source.get("bytes")).longValue();try(InputStream in=zip.getInputStream(entry);OutputStream out=Files.newOutputStream(staged,StandardOpenOption.CREATE_NEW)){in.transferTo(out);}if(Files.size(staged)!=expected||!checksum.equals(ResourceFiles.checksum(staged)))c.fail("RESOURCE_CORRUPT","Package resource content changed");c.server.storage.admission.materialized(admission,resources.files.root,expected);
            String targetId=imported.create(staged,(String)source.get("filename"),(String)source.getOrDefault("mediaType","application/octet-stream"),((Number)source.get("bytes")).longValue(),checksum,c.timestamp);resourcesMap.put(sourceId,targetId);mapping.put(sourceId,targetId);
        }catch(IOException e){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to materialize package resource");}finally{if(staged!=null)try{Files.deleteIfExists(staged);}catch(IOException ignored){}}}
    for(Object raw:(List<?>)graph.get("objects")){var source=ValueCodec.map(raw);String sourceId=(String)source.get("id"),classId=classes.get((String)source.get("classId"));if(classId==null)c.fail("VALIDATION_FAILED","Object Class is outside the package");Object selected=objectMappings.get(sourceId);Node object=selected instanceof String targetId?repo.entity("Object",namespace,targetId):null;if(selected instanceof String&&object==null)c.fail("PLAN_STALE","A selected matching Object is no longer available");if(object==null){object=repo.create("Object",namespace,c.actorId,Json.map("position",source.getOrDefault("position",List.of(0.0,0.0))));repo.assign(object,repo.entity("Class",namespace,classId),c.actorId);createdObjects++;}else if(!Objects.equals(CoreRepository.id(repo.objectClass(object)),classId))c.fail("PLAN_STALE","A selected matching Object changed Class");String targetId=CoreRepository.id(object);objects.put(sourceId,targetId);mapping.put(sourceId,targetId);}
    for(Object raw:(List<?>)graph.get("memberships")){var m=ValueCodec.map(raw);String objectId=objects.get(m.get("objectId"));Node object=objectId==null?null:repo.entity("Object",namespace,objectId);if(object==null)c.fail("VALIDATION_FAILED","Membership Object is outside the package");memberships.put((String)m.get("id"),CoreRepository.id(repo.membership(object)));}
    for(Object raw:(List<?>)graph.get("descriptors")){var source=ValueCodec.map(raw);String sourceObject=(String)source.get("objectId"),targetObject=objects.get(sourceObject);if(targetObject==null)c.fail("VALIDATION_FAILED","Descriptor owner is outside the package");Node object=repo.entity("Object",namespace,targetObject),clazz=repo.objectClass(object);String name=(String)source.get("name");Node declaration=repo.declaration(clazz,name);if(declaration==null)c.fail("VALIDATION_FAILED","Descriptor declaration is missing");String type=(String)declaration.getProperty("type");Object incoming=ExchangePackageCodec.decodeValue(type,source.get("value")),value=incoming;if("DResource".equals(type)&&value!=null){String mappedResource=resourcesMap.get((String)ValueCodec.map(value).get("resourceId"));if(mappedResource==null)c.fail("RESOURCE_CORRUPT","Package resource reference is missing");value=Json.map("resourceId",mappedResource);}Node descriptor=repo.descriptor(object,name);if(descriptor==null){descriptor=repo.attach("Descriptor",object,c.actorId,Json.map("name",name));repo.writeImportedValue(descriptor,type,value,c.actorId,imported);}else {boolean equal="DResource".equals(type)?ImportSemantics.resourceEquivalent(resources,incoming,repo.descriptorValue(descriptor),graph,namespace):Objects.equals(repo.descriptorValue(descriptor),value);if(!equal){c.fail("PLAN_STALE","Matched Descriptor changed after analysis");}}descriptors.put((String)source.get("id"),CoreRepository.id(descriptor));if("DGeometry".equals(type)&&source.get("geometryId") instanceof String sourceGeometry){Node geometry=new GeometryRepository(c.tx,repo).geometry(descriptor);if(geometry!=null){String targetId=CoreRepository.id(geometry);geometriesMap.put(sourceGeometry,targetId);mapping.put(sourceGeometry,targetId);}} }
    for(Object raw:(List<?>)graph.get("relationships")){var edge=ValueCodec.map(raw);Node source=repo.entity("Object",namespace,objects.get(edge.get("sourceId"))),target=repo.entity("Object",namespace,objects.get(edge.get("targetId")));if(source==null||target==null)c.fail("VALIDATION_FAILED","Relationship endpoint is outside the package");var relation=repo.relationship(source,target,(String)edge.get("label"));if(relation==null)relation=repo.createRelationship(source,target,(String)edge.get("label"),edge.containsKey("weight")?Json.map("weight",edge.get("weight")):Map.of(),c.actorId);relationships.put((String)edge.get("id"),CoreRepository.id(relation));}
    for(Object raw:(List<?>)graph.get("classRelationships")){var edge=ValueCodec.map(raw);Node source=repo.entity("Class",namespace,classes.get(edge.get("sourceId"))),target=repo.entity("Class",namespace,classes.get(edge.get("targetId")));if(source==null||target==null)c.fail("VALIDATION_FAILED","Class Relationship endpoint is outside the package");var relation=repo.relationship(source,target,(String)edge.get("label"));if(relation==null)relation=repo.createRelationship(source,target,(String)edge.get("label"),Map.of(),c.actorId);classRelationships.put((String)edge.get("id"),CoreRepository.id(relation));}
    Map<String,String> mappedNames=new AbstractMap<>(){@Override public String get(Object key){Object value=classMap.get(key);return value instanceof String text?text:null;}@Override public Set<Entry<String,String>> entrySet(){return Collections.emptySet();}};for(Object raw:(List<?>)graph.get("patterns")){var p=ValueCodec.map(raw);Map<String,Object> definition=ImportSemantics.mapPatternClasses(ValueCodec.map(p.get("definition")),mappedNames);Node existing=repo.find("Pattern",Map.of("namespaceId",namespace,"name",p.get("name")));if(existing==null)existing=repo.create("Pattern",namespace,c.actorId,Json.map("name",p.get("name"),"definition",Json.write(definition),"comment",p.getOrDefault("comment","")));else if(!ImportSemantics.samePatternDefinition((String)existing.getProperty("definition"),definition,ImportSemantics.namespaceSchema(repo,namespace))||!Objects.equals(existing.getProperty("comment",""),p.getOrDefault("comment","")))c.fail("IMPORT_CONFLICT","Pattern name conflicts");patterns.put((String)p.get("id"),CoreRepository.id(existing));}
    for(Object raw:(List<?>)graph.get("userTools")){var tool=ValueCodec.map(raw);Node existing=repo.find("UserTool",Map.of("namespaceId",namespace,"name",tool.get("name")));if(existing==null)existing=repo.create("UserTool",namespace,c.actorId,Json.map("name",tool.get("name"),"data",tool.get("data"),"comment",tool.getOrDefault("comment","")));else if(!Objects.equals(existing.getProperty("data"),tool.get("data")))c.fail("IMPORT_CONFLICT","User Tool name conflicts");userTools.put((String)tool.get("id"),CoreRepository.id(existing));}
    new PatternService(c).validateNamespace(namespace);var identityMappings=Json.map("Namespace",Map.of((String)pkg.manifest().get("sourceNamespaceId"),namespace),"Class",classes,"Object",objects,"DResource",resourcesMap,"DGeometry",geometriesMap,"Descriptor",descriptors,"Membership",memberships,"Relationship",relationships,"ClassRelationship",classRelationships,"Pattern",patterns,"UserTool",userTools);return Json.map("mapping",mapping,"identityMappings",identityMappings,"resourceMapping",resourcesMap,"createdClasses",longCount(classes),"createdObjects",createdObjects,"createdResources",longCount(resourcesMap),"createdRelationships",longSize((Collection<?>)graph.get("relationships")));
    }
}

private static long longCount(Map<?,?> values){long count=0;for(Object ignored:values.keySet())count=Math.addExact(count,1);return count;}
private static long longSize(Collection<?> values){return values instanceof LongSized sized?sized.longSize():values.size();}

/** Existing-Namespace imports publish only resources reachable from current typed Descriptor values. */
static Set<String> currentDescriptorResourceIds(Map<String,Object> graph){
    var declarations=new HashMap<String,Map<String,String>>();
    for(Object raw:(List<?>)graph.get("classes")){var clazz=ValueCodec.map(raw);var fields=new HashMap<String,String>();for(Object fieldRaw:(List<?>)clazz.get("descriptors")){var field=ValueCodec.map(fieldRaw);fields.put((String)field.get("name"),(String)field.get("type"));}declarations.put((String)clazz.get("id"),fields);}
    var objectClasses=new HashMap<String,String>();for(Object raw:(List<?>)graph.get("objects")){var object=ValueCodec.map(raw);objectClasses.put((String)object.get("id"),(String)object.get("classId"));}
    var resources=new HashSet<String>();
    for(Object raw:(List<?>)graph.get("descriptors")){var descriptor=ValueCodec.map(raw);String classId=objectClasses.get(descriptor.get("objectId"));String type=declarations.getOrDefault(classId,Map.of()).get(descriptor.get("name"));if(!"DResource".equals(type)||descriptor.get("value")==null)continue;Object value=ExchangePackageCodec.decodeValue(type,descriptor.get("value"));if(value instanceof Map<?,?> reference&&reference.get("resourceId") instanceof String id)resources.add(id);}
    return resources;
}

/** Disk-backed production variant for large imports. */
static Set<String> currentDescriptorResourceIds(Map<String,Object> graph,ImportScratch scratch){
    Map<String,String> declarations=scratch.map(),objectClasses=scratch.map();Map<String,Boolean> resources=scratch.map();
    for(Object raw:(List<?>)graph.get("classes")){var clazz=ValueCodec.map(raw);String classId=(String)clazz.get("id");for(Object fieldRaw:(List<?>)clazz.get("descriptors")){var field=ValueCodec.map(fieldRaw);declarations.put(Json.write(List.of(classId,field.get("name"))),(String)field.get("type"));}}
    for(Object raw:(List<?>)graph.get("objects")){var object=ValueCodec.map(raw);objectClasses.put((String)object.get("id"),(String)object.get("classId"));}
    for(Object raw:(List<?>)graph.get("descriptors")){var descriptor=ValueCodec.map(raw);String classId=objectClasses.get(descriptor.get("objectId"));String type=classId==null?null:declarations.get(Json.write(List.of(classId,descriptor.get("name"))));if(!"DResource".equals(type)||descriptor.get("value")==null)continue;Object value=ExchangePackageCodec.decodeValue(type,descriptor.get("value"));if(value instanceof Map<?,?> reference&&reference.get("resourceId") instanceof String id)resources.put(id,true);}
    return new AbstractSet<>(){@Override public boolean contains(Object value){return resources.containsKey(value);}@Override public int size(){return resources.size();}@Override public Iterator<String> iterator(){var rows=resources.entrySet().iterator();return new Iterator<>(){public boolean hasNext(){return rows.hasNext();}public String next(){return rows.next().getKey();}};}};
}

}
