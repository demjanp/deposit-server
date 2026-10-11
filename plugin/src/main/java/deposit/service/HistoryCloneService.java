package deposit.service;

import deposit.common.*;
import deposit.repository.*;
import java.time.*;
import java.util.*;
import org.neo4j.graphdb.*;

/** Rebuilds a validated portable history bundle with destination-local identities. */
final class HistoryCloneService {
    private static final Map<String,String> LABELS=Map.ofEntries(
        Map.entry("Namespace","Namespace"),Map.entry("Class","Class"),Map.entry("Object","Object"),
        Map.entry("ClassDescriptor","ClassDescriptor"),Map.entry("Descriptor","Descriptor"),
        Map.entry("DGeometry","DGeometry"),Map.entry("Pattern","Pattern"),Map.entry("UserTool","UserTool"),
        Map.entry("Relationship","Relationship"),Map.entry("ClassRelationship","ClassRelationship"),
        Map.entry("Membership","Membership"),Map.entry("Revision","Revision"),Map.entry("Epoch","DepositHistoryEpoch"));
    private final Transaction tx;private final CoreRepository core;private final ResourceRepository resources;
    private final String namespace,actor,runId;private final Map<String,String> resourceMap;
    private final Map<String,Map<String,String>> ids=new HashMap<>();
    HistoryCloneService(Transaction tx,CoreRepository core,String namespace,String actor,String runId,Map<String,Object> identityMappings,Map<String,String> resourceMap){this.tx=tx;this.core=core;this.resources=new ResourceRepository(tx,core);this.namespace=namespace;this.actor=actor;this.runId=runId;this.resourceMap=resourceMap;for(var entry:identityMappings.entrySet())if(entry.getValue() instanceof Map<?,?> map){Map<String,String> category=ids.computeIfAbsent(entry.getKey(),k->new HashMap<>());ValueCodec.map(map).forEach((source,target)->{if(target instanceof String id)category.put(source,id);});}}

    void importHistory(Map<String,Object> bundle){
        if(bundle==null)return;
        if(!"persistent".equals(bundle.get("historyMode")))throw ExceptionHandler.fail("VALIDATION_FAILED","Only persistent history bundles can be cloned");
        Node root=core.namespace(namespace);if(root==null)throw ExceptionHandler.fail("VALIDATION_FAILED","Clone Namespace is missing");
        allocate("Namespace",(String)bundle.get("namespaceId"),namespace);
        List<?> epochs=(List<?>)bundle.get("epochs");
        for(Object raw:epochs){Map<String,Object> epoch=ValueCodec.map(raw);allocate("Epoch",(String)epoch.get("id"),UUID.randomUUID().toString());for(Object rr:(List<?>)epoch.get("revisions")){Map<String,Object> revision=ValueCodec.map(rr);allocate("Revision",(String)revision.get("revisionId"),UUID.randomUUID().toString());for(Object cr:(List<?>)revision.get("changes")){Map<String,Object> change=ValueCodec.map(cr);allocate((String)change.get("entityKind"),(String)change.get("entityId"),UUID.randomUUID().toString());for(String side:List.of("before","after")){Object image=change.get(side);if(image instanceof Map<?,?>)collectImage(ValueCodec.map(image));}}}}
        for(var entry:resourceMap.entrySet())allocate("DResource",entry.getKey(),entry.getValue());
        for(Object raw:epochs)writeEpoch(ValueCodec.map(raw));
        root.setProperty("historyMode","persistent");root.setProperty("historyEpochId",mapped("Epoch",(String)bundle.get("activeEpochId")));root.setProperty("headRevisionId",mapped("Revision",(String)bundle.get("headRevisionId")));
    }

    private void collectImage(Map<String,Object> image){Object kind=image.get("kind"),id=image.get("id");if(kind instanceof String k&&id instanceof String i)allocate(k,i,UUID.randomUUID().toString());for(String field:List.of("classId","objectId","sourceId","targetId","descriptorId","geometryId","namespaceId")){Object ref=image.get(field);if(ref instanceof String source){String refKind=fieldKind(field);if(field.equals("sourceId")||field.equals("targetId"))refKind="ClassRelationship".equals(kind)?"Class":"Object";if(LABELS.containsKey(refKind))allocate(refKind,source,UUID.randomUUID().toString());}}Object value=image.get("value");if(value instanceof Map<?,?> map&&ValueCodec.map(value).get("resourceId") instanceof String rid)allocate("DResource",rid,resourceMap.getOrDefault(rid,UUID.randomUUID().toString()));}
    private String fieldKind(String field){return switch(field){case "classId"->"Class";case "objectId"->"Object";case "sourceId","targetId"->"RelationshipEndpoint";case "descriptorId"->"Descriptor";case "geometryId"->"DGeometry";case "namespaceId"->"Namespace";default->"";};}
    private void allocate(String kind,String source,String target){if(source==null||target==null)return;ids.computeIfAbsent(kind,k->new HashMap<>()).putIfAbsent(source,target);}
    private String mapped(String kind,String source){if(source==null)return null;return ids.computeIfAbsent(kind,k->new HashMap<>()).computeIfAbsent(source,k->UUID.randomUUID().toString());}
    private void writeEpoch(Map<String,Object> source){String epochId=mapped("Epoch",(String)source.get("id"));Map<String,Object> properties=Json.map("id",epochId,"namespaceId",namespace,"mode",source.get("mode"),"bootId",runId,"closed",source.get("closed"),"expired",false,"startedSequence",source.get("startedSequence"),"baselineRevisionId",mapped("Revision",(String)source.get("baselineRevisionId")));
        if(source.get("endedSequence") instanceof Number)properties.put("endedSequence",source.get("endedSequence"));Node epoch=tx.createNode(Label.label("DepositHistoryEpoch"));set(epoch,properties);core.namespace(namespace).createRelationshipTo(epoch,RelationshipType.withName("HAS_HISTORY_EPOCH"));
        for(Object raw:(List<?>)source.get("revisions"))writeRevision(epoch,ValueCodec.map(raw),epochId);
    }
    private void writeRevision(Node epoch,Map<String,Object> source,String epochId){String id=mapped("Revision",(String)source.get("revisionId"));var properties=new LinkedHashMap<String,Object>();properties.put("id",id);properties.put("namespaceId",namespace);properties.put("sequence",source.get("sequence"));properties.put("historyEpochId",epochId);properties.put("historyMode",source.get("historyMode"));properties.put("operationId",UUID.randomUUID().toString());properties.put("actorId",source.getOrDefault("actorId",actor));properties.put("committedAt",temporal(source.get("committedAt")));properties.put("kind",source.get("kind"));properties.put("summary",source.getOrDefault("summary","Imported history"));String parent=mapped("Revision",(String)source.get("parentRevisionId"));if(parent!=null)properties.put("parentRevisionId",parent);
        for(String link:List.of("reversesRevisionId","reappliesRevisionId","restoresRevisionId")){String target=mapped("Revision",(String)source.get(link));if(target!=null)properties.put(link,target);}if("transient".equals(source.get("historyMode")))properties.put("bootId",runId);
        var journal=Json.map("touches",remapTouches(ValueCodec.map(source.get("touches"))),"dependencies",remapDependencies((List<?>)source.get("dependencies")));properties.put("journal",Json.write(journal));Node revision=tx.createNode(Label.label("Revision"));set(revision,properties);epoch.createRelationshipTo(revision,RelationshipType.withName("HAS_REVISION"));
        for(Object raw:(List<?>)source.get("changes"))writeSnapshot(revision,ValueCodec.map(raw),id,epochId);
        Object tag=source.get("tag");if(tag instanceof String text&&!text.isEmpty()){Node tagNode=tx.createNode(Label.label("RevisionTag"));set(tagNode,Json.map("id",UUID.randomUUID().toString(),"namespaceId",namespace,"revisionId",id,"tag",text,"updatedAt",temporal(source.get("committedAt")),"updatedBy",actor));revision.createRelationshipTo(tagNode,RelationshipType.withName("HAS_TAG"));}
    }
    private void writeSnapshot(Node revision,Map<String,Object> source,String revisionId,String epochId){String kind=(String)source.get("entityKind"),entityId=mapped(kind,(String)source.get("entityId"));Map<String,Object> before=source.get("before") instanceof Map<?,?>?remapImage(ValueCodec.map(source.get("before"))):null,after=source.get("after") instanceof Map<?,?>?remapImage(ValueCodec.map(source.get("after"))):null;Node snapshot=tx.createNode(Label.label("DepositSnapshot"));set(snapshot,Json.map("id",UUID.randomUUID().toString(),"namespaceId",namespace,"revisionId",revisionId,"historyEpochId",epochId,"entityId",entityId,"entityKind",kind,"beforeExists",before!=null,"afterExists",after!=null,"beforeJson",before==null?null:Json.write(before),"afterJson",after==null?null:Json.write(after)));revision.createRelationshipTo(snapshot,RelationshipType.withName("HAS_CHANGE"));retain(snapshot,before);retain(snapshot,after);}
    private Map<String,Object> remapImage(Map<String,Object> source){var out=new LinkedHashMap<String,Object>(source);String kind=(String)out.get("kind");if(out.get("id") instanceof String id)out.put("id",mapped(kind,id));if(out.containsKey("namespaceId"))out.put("namespaceId",namespace);for(String field:List.of("classId","objectId","descriptorId","geometryId")){String targetKind=fieldKind(field);if(out.get(field) instanceof String id)out.put(field,mapped(targetKind,id));}for(String field:List.of("sourceId","targetId")){if(out.get(field) instanceof String id)out.put(field,mapped("ClassRelationship".equals(kind)?"Class":"Object",id));}Object value=out.get("value");if(value instanceof Map<?,?> map&&ValueCodec.map(value).get("resourceId") instanceof String rid){var remapped=new LinkedHashMap<>(ValueCodec.map(value));remapped.put("resourceId",mapped("DResource",rid));out.put("value",remapped);}return out;}
    private Map<String,Object> remapTouches(Map<String,Object> source){var out=new TreeMap<String,Object>();source.forEach((key,value)->out.put(remapKey(key),value));return out;}
    private List<String> remapDependencies(List<?> source){var out=new ArrayList<String>();for(Object raw:source)out.add(remapKey((String)raw));return out;}
    private String remapKey(String key){int separator=key.indexOf('\u0000');if(separator<1)return key;String kind=key.substring(0,separator),id=key.substring(separator+1);return HistoryCodec.key(kind,mapped(kind,id));}
    private void retain(Node snapshot,Map<String,Object> image){if(image==null||!"Descriptor".equals(image.get("kind"))||!(image.get("value") instanceof Map<?,?> value)||!ValueCodec.map(value).keySet().equals(Set.of("resourceId")))return;String id=(String)ValueCodec.map(value).get("resourceId");if(id==null)return;Node resource=resources.resource(namespace,id);if(resource==null)throw ExceptionHandler.fail("RESOURCE_CORRUPT","Historical resource content is missing from clone");try(var links=snapshot.getRelationships(Direction.OUTGOING,RelationshipType.withName("RETAINS_RESOURCE"))){for(var edge:links)if(edge.getEndNode().equals(resource))return;}snapshot.createRelationshipTo(resource,RelationshipType.withName("RETAINS_RESOURCE"));}
    private Object temporal(Object value){if(!(value instanceof String text))return value;try{return ZonedDateTime.parse(text);}catch(Exception ignored){return OffsetDateTime.parse(text).toZonedDateTime();}}
    private void set(Node node,Map<String,Object> values){values.forEach((key,value)->{if(value!=null){Object v=value;if(v instanceof Map<?,?>||v instanceof Collection<?>)v=Json.write(v);node.setProperty(key,v);}});}
}
