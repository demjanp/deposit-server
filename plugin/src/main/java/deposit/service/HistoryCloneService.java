package deposit.service;

import deposit.common.*;
import deposit.repository.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.*;
import java.util.*;
import org.neo4j.graphdb.*;

/** Rebuilds portable history with disk-backed destination identity indexes. */
final class HistoryCloneService {
    private static final Set<String> LABELS=Set.of(
        "Namespace","Class","Object","ClassDescriptor","Descriptor","DGeometry","Pattern","UserTool",
        "Relationship","ClassRelationship","Membership","Revision","Epoch");
    private final RequestContext context;
    private final Transaction tx;
    private final CoreRepository core;
    private final ResourceRepository resources;
    private final String namespace,actor,runId;
    private final Map<String,String> resourceMap;
    private final Map<String,Object> identityMappings;
    private final Map<String,Map<String,String>> addedIds=new HashMap<>();
    private ImportScratch scratch;
    private ImportWriteBudget writeBudget;
    private Map<String,String> historyMemberships,historyDeclarations,descriptorBeforeTypes;
    private Map<String,Collection<?>> revisionTouches;

    @SuppressWarnings("unchecked")
    HistoryCloneService(RequestContext context,String namespace,Map<String,Object> identityMappings,Map<String,String> resourceMap){
        this.context=context;this.tx=context.tx;this.core=new CoreRepository(tx,context.timestamp);this.resources=new ResourceRepository(tx,core);
        this.namespace=namespace;this.actor=context.actorId;this.runId=context.server.runId;
        this.identityMappings=identityMappings;this.resourceMap=resourceMap;
    }

    void importHistory(Map<String,Object> bundle){
        if(bundle==null)return;
        if(!"persistent".equals(bundle.get("historyMode")))throw ExceptionHandler.fail("VALIDATION_FAILED","Only persistent history bundles can be cloned");
        Node root=core.namespace(namespace);if(root==null)throw ExceptionHandler.fail("VALIDATION_FAILED","Clone Namespace is missing");
        try {
            writeBudget=ImportWriteBudget.forRequest(context,"server",namespace);
            var scratchBudget=writeBudget.scratchLease();
            scratch=new ImportScratch(resources.files.path("staging/imports"),()->context.state.kernel.assertOpen(),scratchBudget::reserve,scratchBudget::close);
        } catch(IOException failure) { throw ExceptionHandler.fail("RESOURCE_CORRUPT","Unable to create private history identity index"); }
        context.state.release.add(()->{try{scratch.close();}catch(IOException failure){context.server.storage.diagnostic="Clone history identity cleanup pending";}});
        historyMemberships=scratch.orderedMap();historyDeclarations=scratch.orderedMap();descriptorBeforeTypes=scratch.orderedMap();revisionTouches=scratch.orderedMap();

        allocate("Namespace",(String)bundle.get("namespaceId"),namespace);
        List<?> epochs=(List<?>)bundle.get("epochs");
        // Allocate every serialized identity before writing any row. References never synthesize
        // IDs: only declared history entities and touch/dependency identities receive new IDs.
        for(Object raw:epochs){
            Map<String,Object> epoch=ValueCodec.map(raw);
            allocate("Epoch",(String)epoch.get("id"),UUID.randomUUID().toString());
            for(Object revisionRaw:(List<?>)epoch.get("revisions")){
                Map<String,Object> revision=ValueCodec.map(revisionRaw);
                allocate("Revision",(String)revision.get("revisionId"),UUID.randomUUID().toString());
                for(var touch:ValueCodec.map(revision.get("touches")).entrySet())allocateKey((String)touch.getKey());
                for(Object dependency:(List<?>)revision.get("dependencies"))allocateKey((String)dependency);
                for(Object changeRaw:(List<?>)revision.get("changes")){
                    Map<String,Object> change=ValueCodec.map(changeRaw);
                    allocate((String)change.get("entityKind"),(String)change.get("entityId"),UUID.randomUUID().toString());
                    for(String side:List.of("before","after")){Object image=change.get(side);if(image instanceof Map<?,?>)collectImage(ValueCodec.map(image));}
                }
            }
        }
        // These maps are already SQLite-backed by ImportSnapshotMaterializer and include all
        // package resources, including content referenced only by history.
        for(var entry:resourceMap.entrySet())allocate("DResource",entry.getKey(),entry.getValue());
        for(Object raw:epochs)writeEpoch(ValueCodec.map(raw));
        writeBudget.reserveBeforeMutation(root);
        root.setProperty("historyMode","persistent");
        root.setProperty("historyEpochId",mapped("Epoch",(String)bundle.get("activeEpochId")));
        root.setProperty("headRevisionId",mapped("Revision",(String)bundle.get("headRevisionId")));
        publishHistoryMappings();
    }

    private void allocateKey(String key){int separator=key.indexOf('\u0000');if(separator<=0||separator==key.length()-1)throw ExceptionHandler.fail("RESOURCE_CORRUPT","History identity key is invalid");allocate(key.substring(0,separator),key.substring(separator+1),UUID.randomUUID().toString());}
    private void collectImage(Map<String,Object> image){
        Object kind=image.get("kind"),id=image.get("id");if(kind instanceof String k&&id instanceof String i)allocate(k,i,UUID.randomUUID().toString());
        for(String field:List.of("classId","objectId","sourceId","targetId","descriptorId","geometryId","namespaceId")){
            Object ref=image.get(field);if(ref instanceof String source){String refKind=fieldKind(field);if(field.equals("sourceId")||field.equals("targetId"))refKind="ClassRelationship".equals(kind)?"Class":"Object";if(LABELS.contains(refKind))allocate(refKind,source,UUID.randomUUID().toString());}
        }
        Object value=image.get("value");if(value instanceof Map<?,?> map&&ValueCodec.map(value).get("resourceId") instanceof String rid&&!resourceMap.containsKey(rid))throw ExceptionHandler.fail("RESOURCE_CORRUPT","Historical resource mapping is missing from clone");
    }
    private String fieldKind(String field){return switch(field){case "classId"->"Class";case "objectId"->"Object";case "sourceId","targetId"->"RelationshipEndpoint";case "descriptorId"->"Descriptor";case "geometryId"->"DGeometry";case "namespaceId"->"Namespace";default->"";};}
    @SuppressWarnings("unchecked")
    private Map<String,String> added(String kind){return addedIds.computeIfAbsent(kind,ignored->scratch.orderedMap());}
    @SuppressWarnings("unchecked")
    private Map<String,String> base(String kind){Object raw=identityMappings.get(kind);return raw instanceof Map<?,?>?(Map<String,String>)raw:Map.of();}
    private void allocate(String kind,String source,String target){if(source==null||target==null||!LABELS.contains(kind)&&!"DResource".equals(kind))return;Map<String,String> sourceMap=base(kind);if(sourceMap.containsKey(source))return;added(kind).putIfAbsent(source,target);}
    private String mapped(String kind,String source){
        if(source==null)return null;
        String target=base(kind).get(source);if(target!=null)return target;
        target=added(kind).get(source);if(target!=null)return target;
        throw ExceptionHandler.fail("RESOURCE_CORRUPT","History reference has no allocated destination identity");
    }

    private void publishHistoryMappings(){
        for(var entry:addedIds.entrySet()){
            Map<String,String> original=base(entry.getKey()),extra=entry.getValue();
            identityMappings.put(entry.getKey(),combined(original,extra));
        }
    }
    private Map<String,String> combined(Map<String,String> original,Map<String,String> extra){
        return new AbstractMap<>(){
            public String get(Object key){String value=original.get(key);return value!=null?value:extra.get(key);}
            public boolean containsKey(Object key){return original.containsKey(key)||extra.containsKey(key);}
            public int size(){return Math.addExact(original.size(),extra.size());}
            public Set<Entry<String,String>> entrySet(){return new AbstractSet<>(){
                public int size(){return Math.addExact(original.size(),extra.size());}
                public Iterator<Entry<String,String>> iterator(){Iterator<Entry<String,String>> first=original.entrySet().iterator(),second=extra.entrySet().iterator();return new Iterator<>(){public boolean hasNext(){return first.hasNext()||second.hasNext();}public Entry<String,String> next(){return first.hasNext()?first.next():second.next();}};}
            };}
        };
    }

    private Node node(String label,Map<String,Object> properties,int ownedRelationships){writeBudget.reserveHistoryRecord(properties,ownedRelationships);Node node=tx.createNode(Label.label(label));set(node,properties);return node;}
    private void writeEpoch(Map<String,Object> source){
        historyMemberships.clear();historyDeclarations.clear();descriptorBeforeTypes.clear();
        String epochId=mapped("Epoch",(String)source.get("id"));
        Map<String,Object> properties=Json.map("id",epochId,"namespaceId",namespace,"mode",source.get("mode"),"bootId",runId,"closed",source.get("closed"),"expired",false,"startedSequence",source.get("startedSequence"),"baselineRevisionId",mapped("Revision",(String)source.get("baselineRevisionId")));
        if(source.get("endedSequence") instanceof Number)properties.put("endedSequence",source.get("endedSequence"));
        Node epoch=node("DepositHistoryEpoch",properties,1);core.namespace(namespace).createRelationshipTo(epoch,RelationshipType.withName("HAS_HISTORY_EPOCH"));
        for(Object raw:(List<?>)source.get("revisions"))writeRevision(epoch,ValueCodec.map(raw),epochId);
    }
    private void writeRevision(Node epoch,Map<String,Object> source,String epochId){
        String id=mapped("Revision",(String)source.get("revisionId"));var touches=ValueCodec.map(source.get("touches"));var dependencies=(List<?>)source.get("dependencies");var changes=(List<?>)source.get("changes");
        descriptorBeforeTypes.clear();
        for(Object raw:changes){Map<String,Object> change=ValueCodec.map(raw);if(!"Descriptor".equals(change.get("entityKind"))||!(change.get("before") instanceof Map<?,?>))continue;Map<String,Object> before=ValueCodec.map(change.get("before"));descriptorBeforeTypes.put((String)change.get("entityId"),descriptorType(before));}
        // Reconstruct the schema and memberships for this revision as one state transition.
        // All before-value types above came from the preceding state; all after-value types
        // below come from the complete new state, independent of row order.
        for(Object raw:changes){Map<String,Object> change=ValueCodec.map(raw);String kind=(String)change.get("entityKind");if(Set.of("Membership","ClassDescriptor").contains(kind))updateDeclarationState(kind,change.get("before"),null);}
        for(Object raw:changes){Map<String,Object> change=ValueCodec.map(raw);String kind=(String)change.get("entityKind");if(Set.of("Membership","ClassDescriptor").contains(kind))updateDeclarationState(kind,null,change.get("after"));}
        var properties=new LinkedHashMap<String,Object>();properties.put("id",id);properties.put("namespaceId",namespace);properties.put("sequence",source.get("sequence"));properties.put("historyEpochId",epochId);properties.put("historyMode",source.get("historyMode"));properties.put("operationId",UUID.randomUUID().toString());properties.put("actorId",source.getOrDefault("actorId",actor));properties.put("committedAt",temporal(source.get("committedAt")));properties.put("kind",source.get("kind"));properties.put("summary",source.getOrDefault("summary","Imported history"));String parent=mapped("Revision",(String)source.get("parentRevisionId"));if(parent!=null)properties.put("parentRevisionId",parent);
        for(String link:List.of("reversesRevisionId","reappliesRevisionId","restoresRevisionId")){Object raw=source.get(link);if(raw instanceof String ref)properties.put(link,mapped("Revision",ref));}
        if("transient".equals(source.get("historyMode")))properties.put("bootId",runId);
        if(source.get("tag") instanceof String tag&&!tag.isEmpty()){
            properties.put("tag",tag);
            properties.put("tagUpdatedAt",properties.get("committedAt"));
            properties.put("tagUpdatedBy",actor);
        }
        Node revision=node("Revision",properties,1);epoch.createRelationshipTo(revision,RelationshipType.withName("HAS_REVISION"));
        var history=new HistoryRepository(tx,core);
        for(Object dependency:dependencies)history.writeDependency(revision,remapKey((String)dependency),writeBudget::reserveHistoryRecord);
        revisionTouches.clear();
        for(var entry:touches.entrySet())
            if(revisionTouches.put(entry.getKey(),(Collection<?>)entry.getValue())!=null)HistoryCodec.invalid("Duplicate history touch identity");
        for(Object raw:changes){
            var change=ValueCodec.map(raw);
            String key=HistoryCodec.key((String)change.get("entityKind"),(String)change.get("entityId"));
            writeSnapshot(history,revision,change,revisionTouches.remove(key));
        }
        for(var entry:revisionTouches.entrySet())
            history.writeSnapshot(revision,remapKey(entry.getKey()),entry.getValue(),null,null,true,0,writeBudget::reserveHistoryRecord);
    }
    private void writeSnapshot(HistoryRepository history,Node revision,Map<String,Object> source,Collection<?> fields){
        String kind=(String)source.get("entityKind"),sourceEntityId=(String)source.get("entityId"),entityId=mapped(kind,sourceEntityId);Map<String,Object> before=source.get("before") instanceof Map<?,?>?remapImage(ValueCodec.map(source.get("before")),"Descriptor".equals(kind)?descriptorBeforeTypes.get(sourceEntityId):null):null,after=source.get("after") instanceof Map<?,?>?remapImage(ValueCodec.map(source.get("after")),"Descriptor".equals(kind)?descriptorType(ValueCodec.map(source.get("after"))):null):null;
        int retains=(retainedResource(before)?1:0)+(retainedResource(after)?1:0);
        Node snapshot=history.writeSnapshot(revision,HistoryCodec.key(kind,entityId),fields,before,after,false,retains,writeBudget::reserveHistoryRecord);
        retain(snapshot,before);retain(snapshot,after);
    }
    private Map<String,Object> remapImage(Map<String,Object> source,String descriptorType){
        var out=new LinkedHashMap<String,Object>(source);String kind=(String)out.get("kind");if(out.get("id") instanceof String id)out.put("id",mapped(kind,id));if(out.containsKey("namespaceId"))out.put("namespaceId",namespace);
        for(String field:List.of("classId","objectId","descriptorId","geometryId")){String targetKind=fieldKind(field);if(out.get(field) instanceof String id)out.put(field,mapped(targetKind,id));}
        for(String field:List.of("sourceId","targetId")){if(out.get(field) instanceof String id)out.put(field,mapped("ClassRelationship".equals(kind)?"Class":"Object",id));}
        Object value=out.get("value");if("Descriptor".equals(kind)&&descriptorType!=null&&value!=null){try{value=ExchangePackageCodec.decodeHistoryValue(descriptorType,value);}catch(IOException invalid){throw ExceptionHandler.fail("RESOURCE_CORRUPT","Historical Descriptor value does not match its reconstructed declaration");}out.put("value",value);}
        if(value instanceof Map<?,?> map&&ValueCodec.map(value).get("resourceId") instanceof String rid){var remapped=new LinkedHashMap<>(ValueCodec.map(value));remapped.put("resourceId",mapped("DResource",rid));out.put("value",remapped);}return out;
    }
    private String descriptorType(Map<String,Object> descriptor){String object=(String)descriptor.get("objectId"),classId=historyMemberships.get(object);if(classId==null)throw ExceptionHandler.fail("RESOURCE_CORRUPT","Historical Descriptor has no declaration-state Membership");String type=historyDeclarations.get(classId+"\u0000"+descriptor.get("name"));if(type==null)throw ExceptionHandler.fail("RESOURCE_CORRUPT","Historical Descriptor declaration is missing");return type;}
    private void updateDeclarationState(String kind,Object rawBefore,Object rawAfter){
        if(rawBefore instanceof Map<?,?>){Map<String,Object> before=ValueCodec.map(rawBefore);if(kind.equals("Membership"))historyMemberships.remove((String)before.get("objectId"));else historyDeclarations.remove(before.get("classId")+"\u0000"+before.get("name"));}
        if(rawAfter instanceof Map<?,?>){Map<String,Object> after=ValueCodec.map(rawAfter);if(kind.equals("Membership"))historyMemberships.put((String)after.get("objectId"),(String)after.get("classId"));else historyDeclarations.put(after.get("classId")+"\u0000"+after.get("name"),(String)after.get("type"));}
    }
    private boolean retainedResource(Map<String,Object> image){return image!=null&&"Descriptor".equals(image.get("kind"))&&image.get("value") instanceof Map<?,?> value&&ValueCodec.map(value).keySet().equals(Set.of("resourceId"))&&ValueCodec.map(value).get("resourceId") instanceof String;}
    private String remapKey(String key){int separator=key.indexOf('\u0000');if(separator<1)throw ExceptionHandler.fail("RESOURCE_CORRUPT","History identity key is invalid");String kind=key.substring(0,separator),id=key.substring(separator+1);return HistoryCodec.key(kind,mapped(kind,id));}
    private void retain(Node snapshot,Map<String,Object> image){if(!retainedResource(image))return;String id=(String)ValueCodec.map(image.get("value")).get("resourceId");Node resource=resources.resource(namespace,id);if(resource==null)throw ExceptionHandler.fail("RESOURCE_CORRUPT","Historical resource content is missing from clone");try(var links=snapshot.getRelationships(Direction.OUTGOING,RelationshipType.withName("RETAINS_RESOURCE"))){for(var edge:links)if(edge.getEndNode().equals(resource))return;}snapshot.createRelationshipTo(resource,RelationshipType.withName("RETAINS_RESOURCE"));}
    private Object temporal(Object value){if(!(value instanceof String text))return value;try{return ZonedDateTime.parse(text);}catch(Exception ignored){return OffsetDateTime.parse(text).toZonedDateTime();}}
    private void set(Node node,Map<String,Object> values){values.forEach((key,value)->{if(value!=null){Object v=value;if(v instanceof Map<?,?>||v instanceof Collection<?>)v=Json.write(v);node.setProperty(key,v);}});}
}
