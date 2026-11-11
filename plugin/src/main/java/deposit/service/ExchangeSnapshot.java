package deposit.service;

import deposit.common.*;
import deposit.repository.CoreRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.RelationshipType;

/** Collects and selects a revision-pinned export view; it does not publish resources or receipts. */
final class ExchangeSnapshot {
    private final RequestContext c;
    private final CoreRepository repo;
    private final ExportRecords records;

    ExchangeSnapshot(RequestContext context, CoreRepository repository, ExportRecords records) {
        this.c=context;
        this.repo=repository;
        this.records=records;
    }

    Map<String,Object> snapshot(boolean wholeNamespace) {
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
            "name",ns.getProperty("name"),"description",ns.getProperty("description",""),"depositVersion",DepositInfo.version(),"historyMode",ns.getProperty("historyMode","disabled"),
            "exportedRevision",pinned,"createdAt",c.timestamp.toString(),"rootIds",List.of(),"scope",wholeNamespace?"namespace":"subgraph","files",Map.of());
        return Json.map("manifest",manifest,"graph",graph);
    }

    HistoryProjection.Projection historyBundle(boolean wholeNamespace,Map<String,Object> graph){
        Set<String> objects=Collections.newSetFromMap(records.<Boolean>index()),patterns=Collections.newSetFromMap(records.<Boolean>index());
        for(Object row:(List<?>)graph.get("objects"))objects.add((String)ValueCodec.map(row).get("id"));
        for(Object row:(List<?>)graph.get("patterns"))patterns.add((String)ValueCodec.map(row).get("id"));
        return HistoryProjection.project(new HistoryService(c).historyExport(records),objects,patterns,wholeNamespace,records);
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
    Map<String,Object> subgraph(Map<String,Object> graph,List<?> rawRoots,int maxHops,boolean rootBoundary){
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
}
