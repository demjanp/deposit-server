package deposit.repository;

import deposit.common.*;
import java.util.*;
import java.time.*;
import org.neo4j.graphdb.*;

/** History-only persistence and portable live images, within the supplied action transaction. */
public final class HistoryRepository {
    private final Transaction tx;private final CoreRepository core;
    public HistoryRepository(Transaction tx,CoreRepository core){this.tx=tx;this.core=core;}
    private static Object portable(Object v){if(v instanceof java.time.temporal.TemporalAccessor)return v.toString();if(v instanceof double[] a)return Arrays.stream(a).boxed().toList();return v;}
    private Map<String,Object> image(Entity n,String kind,String... fields){var m=Json.map("kind",kind);for(String k:List.of("id","namespaceId","createdAt","createdBy","updatedAt","updatedBy"))if(n.hasProperty(k))m.put(k,portable(n.getProperty(k)));for(String k:fields)if(n.hasProperty(k))m.put(k,portable(n.getProperty(k)));return m;}
    public Map<String,Map<String,Object>> capture(String ns){
        var out=new TreeMap<String,Map<String,Object>>();Node root=core.namespace(ns);var n=image(root,"Namespace","name","description");n.put("namespaceId",ns);out.put(ns,n);
        for(String kind:List.of("Class","Object","ClassDescriptor","Descriptor","UserTool"))for(Node node:core.nodes(kind,Map.of("namespaceId",ns))){
            Map<String,Object> m=switch(kind){case "Class"->image(node,kind,"name","position","order","comment");case "Object"->image(node,kind,"position");case "ClassDescriptor"->image(node,kind,"name","type","order","comment");case "Descriptor"->image(node,kind,"name");case "UserTool"->image(node,kind,"name","data","comment");default->throw new IllegalStateException();};
            if(kind.equals("ClassDescriptor"))m.put("classId",node.getProperty("ownerId"));
            if(kind.equals("Descriptor")){
                m.put("objectId",node.getProperty("ownerId"));m.put("value",core.descriptorValue(node));
                var edge=node.getSingleRelationship(RelationshipType.withName("HAS_GEOMETRY"),Direction.OUTGOING);
                if(edge!=null){Node g=edge.getEndNode();m.put("geometryId",CoreRepository.id(g));var gi=image(g,"DGeometry","wkt","geometryType","srid","verticalSrid","axisOrder","dimensions");gi.put("descriptorId",CoreRepository.id(node));out.put(CoreRepository.id(g),gi);}
            }
            if(kind.equals("Object")){var edge=core.membership(node);if(edge==null)throw new IllegalStateException("Object missing membership");out.put(CoreRepository.id(edge),core.data(edge));}
            out.put(CoreRepository.id(node),m);
        }
        for(String kind:List.of("Relationship","ClassRelationship"))for(var edge:core.relationships(ns,kind))out.put(CoreRepository.id(edge),core.data(edge));
        return HistoryCodec.copy(out);
    }
    private Node node(String label,Map<String,Object> props){Node n=tx.createNode(Label.label(label));props.forEach((k,v)->{if(v!=null)n.setProperty(k,v);});return n;}
    public Node epoch(String ns,String id){return core.entity("DepositHistoryEpoch",ns,id);}
    public boolean available(Node revision){Node e=epoch((String)revision.getProperty("namespaceId"),(String)revision.getProperty("historyEpochId"));return e!=null&&!Boolean.TRUE.equals(e.getProperty("expired",false));}
    public List<Revision> revisions(String ns){var out=new ArrayList<Revision>();for(Node n:core.nodes("Revision",Map.of("namespaceId",ns)))if(available(n))out.add(read(n));out.sort(Comparator.comparingLong(Revision::sequence).thenComparing(Revision::id));return out;}
    public Revision revision(String ns,String id){Node n=core.entity("Revision",ns,id);if(n==null||!available(n))throw ExceptionHandler.fail("NOT_FOUND","Revision not found");return read(n);}
    public Revision read(Node n){
        var data=new LinkedHashMap<String,Object>();n.getAllProperties().forEach((k,v)->{if(!k.equals("journal"))data.put(k,portable(v));});data.put("revisionId",CoreRepository.id(n));
        var before=new TreeMap<String,Map<String,Object>>();var after=new TreeMap<String,Map<String,Object>>();var snapshotChanges=new ArrayList<Map<String,Object>>();
        try(var edges=n.getRelationships(Direction.OUTGOING,RelationshipType.withName("HAS_CHANGE"))){for(var e:edges){Node s=e.getEndNode();String id=(String)s.getProperty("entityId"),kind=(String)s.getProperty("entityKind");if(!HistoryCodec.KINDS.contains(kind))HistoryCodec.invalid("Invalid snapshot category");if(!HistoryCodec.KINDS.contains(kind))HistoryCodec.invalid("Invalid snapshot category");for(String side:List.of("before","after"))if(Boolean.TRUE.equals(s.getProperty(side+"Exists"))){Map<String,Object> m;try{m=Json.read((String)s.getProperty(side+"Json"));}catch(RuntimeException failure){HistoryCodec.invalid("Invalid snapshot JSON");throw failure;}if(!kind.equals(m.get("kind"))||!id.equals(m.get("id")))HistoryCodec.invalid("Inconsistent snapshot identity");(side.equals("before")?before:after).put(id,m);}snapshotChanges.add(Json.map("entityId",id,"entityKind",kind,"before",before.get(id),"after",after.get(id)));}}
        var journal=Json.read((String)n.getProperty("journal"));var touches=new TreeMap<String,Set<String>>();ValueCodec.map(journal.get("touches")).forEach((k,v)->{var set=new TreeSet<String>();for(Object f:(List<?>)v)set.add((String)f);touches.put(k,set);});var dependencies=new TreeSet<String>();for(Object id:(List<?>)journal.get("dependencies"))dependencies.add((String)id);
        Node tag=core.find("RevisionTag",Map.of("namespaceId",n.getProperty("namespaceId"),"revisionId",CoreRepository.id(n)));data.put("tag",tag==null?null:tag.getProperty("tag"));
        Node epoch=epoch((String)n.getProperty("namespaceId"),(String)n.getProperty("historyEpochId"));data.put("epoch",Json.map("id",CoreRepository.id(epoch),"mode",epoch.getProperty("mode"),"closed",epoch.getProperty("closed",false),"baselineRevisionId",epoch.getProperty("baselineRevisionId")));
        snapshotChanges.sort(Comparator.comparing(m->(String)m.get("entityId")));data.put("changes",snapshotChanges);var portableTouches=new TreeMap<String,Object>();touches.forEach((id,fields)->portableTouches.put(id,new ArrayList<>(fields)));data.put("touches",portableTouches);
        return new Revision(CoreRepository.id(n),((Number)n.getProperty("sequence")).longValue(),data,before,after,touches,dependencies);
    }
    public Revision append(String ns,String actor,String operation,long sequence,String kind,String summary,ChangeSet changes,String link,String target,ZonedDateTime timestamp){
        Node root=core.namespace(ns);String epoch=(String)root.getProperty("historyEpochId");String id=UUID.randomUUID().toString();
        Node revision=node("Revision",Json.map("id",id,"namespaceId",ns,"sequence",sequence,"historyEpochId",epoch,"historyMode",root.getProperty("historyMode"),"operationId",operation,"actorId",actor,"committedAt",timestamp,"kind",kind,"summary",summary,"parentRevisionId",kind.equals("baseline")?null:root.getProperty("headRevisionId",null),"journal",Json.write(changes.portable())));
        if("transient".equals(root.getProperty("historyMode")))revision.setProperty("bootId",epoch(ns,epoch).getProperty("bootId"));
        if(link!=null)revision.setProperty(link,target);root.createRelationshipTo(revision,RelationshipType.withName("HAS_REVISION"));
        for(String entity:HistoryCodec.union(changes.touches.keySet(),HistoryCodec.union(changes.before.keySet(),changes.after.keySet()))){
            var before=changes.before.get(entity);var after=changes.after.get(entity);if(!changes.touches.containsKey(entity)&&HistoryCodec.equal(HistoryCodec.scientific(before),HistoryCodec.scientific(after)))continue;
            String entityKind=before==null&&after==null?changes.kinds.get(entity):(String)(after==null?before:after).get("kind");
            if(entityKind==null)continue; // An empty-net reversal carries original touches without recreating entities.
            Node snapshot=node("DepositSnapshot",Json.map("id",UUID.randomUUID().toString(),"namespaceId",ns,"revisionId",id,"historyEpochId",epoch,"entityId",entity,"entityKind",entityKind,"beforeExists",before!=null,"afterExists",after!=null,"beforeJson",before==null?null:Json.write(before),"afterJson",after==null?null:Json.write(after)));
            revision.createRelationshipTo(snapshot,RelationshipType.withName("HAS_CHANGE"));retain(ns,snapshot,before,changes.before);retain(ns,snapshot,after,changes.after);
        }
        root.setProperty("headRevisionId",id);return read(revision);
    }
    private void retain(String ns,Node snapshot,Map<String,Object> image,Map<String,Map<String,Object>> state){
        if(image==null||!"Descriptor".equals(image.get("kind"))||image.get("value")==null||!"DResource".equals(HistoryCodec.descriptorType(state,image)))return;
        String id=(String)ValueCodec.map(image.get("value")).get("resourceId");Node resource=validateResource(ns,id);
        try(var links=snapshot.getRelationships(Direction.OUTGOING,RelationshipType.withName("RETAINS_RESOURCE"))){for(var link:links)if(link.getEndNode().equals(resource))return;}
        snapshot.createRelationshipTo(resource,RelationshipType.withName("RETAINS_RESOURCE"));new ResourceRepository(tx,core).orphan(new ResourceRepository(tx,core).location(id));
    }
    public Node validateResource(String ns,String id){var resources=new ResourceRepository(tx,core);resources.lock(id);Node r=resources.resource(ns,id),loc=resources.location(id);if(r==null||loc==null||loc.hasProperty("deleting")||!"ready".equals(r.getProperty("state",null)))throw ExceptionHandler.fail("RESOURCE_CORRUPT","Historical resource unavailable");for(String field:List.of("bytes","checksum"))if(!r.hasProperty(field)||!loc.hasProperty(field)||!Objects.equals(r.getProperty(field,null),loc.getProperty(field,null)))throw ExceptionHandler.fail("RESOURCE_CORRUPT","Historical resource metadata differs");if(!(r.getProperty("bytes") instanceof Number bytes)||bytes.longValue()<0||!(r.getProperty("checksum") instanceof String checksum)||!checksum.matches("[0-9a-f]{64}")||!loc.hasProperty("storedFilename")||!ns.equals(loc.getProperty("namespaceId",null)))throw ExceptionHandler.fail("RESOURCE_CORRUPT","Invalid historical resource metadata");for(String field:List.of("filename","storedFilename","mediaType"))if(!(r.getProperty(field,null) instanceof String))throw ExceptionHandler.fail("RESOURCE_CORRUPT","Missing historical resource metadata");resources.verify(loc);return r;}
    public void validateResources(String ns,Map<String,Map<String,Object>> state){for(var m:state.values())if("Descriptor".equals(m.get("kind"))&&m.get("value")!=null&&"DResource".equals(HistoryCodec.descriptorType(state,m)))validateResource(ns,(String)ValueCodec.map(m.get("value")).get("resourceId"));Node other=core.find("Namespace","name",state.get(ns).get("name"));if(other!=null&&!CoreRepository.id(other).equals(ns))HistoryCodec.invalid("Namespace name is now occupied");}
    public void baseline(String ns,String actor,long sequence,String boot,ZonedDateTime timestamp){
        Node root=core.namespace(ns);if("disabled".equals(root.getProperty("historyMode"))){root.removeProperty("headRevisionId");root.removeProperty("historyEpochId");return;}
        String epochId=UUID.randomUUID().toString();Node epoch=node("DepositHistoryEpoch",Json.map("id",epochId,"namespaceId",ns,"mode",root.getProperty("historyMode"),"bootId",boot,"closed",false,"expired",false,"startedSequence",sequence,"baselineRevisionId","pending"));root.setProperty("historyEpochId",epochId);root.createRelationshipTo(epoch,RelationshipType.withName("HAS_HISTORY_EPOCH"));
        var changes=new ChangeSet(Map.of());changes.checkpoint(capture(ns));Revision baseline=append(ns,actor,null,sequence,"baseline","Epoch baseline",changes,null,null,timestamp);epoch.setProperty("baselineRevisionId",baseline.id());
    }
    public void mode(String ns,String mode,String actor,long sequence,String boot,ZonedDateTime timestamp){
        Node root=core.namespace(ns);String old=(String)root.getProperty("historyMode");Node epoch=root.hasProperty("historyEpochId")?epoch(ns,(String)root.getProperty("historyEpochId")):null;
        root.setProperty("historyMode",mode);
        if(old.equals("transient")&&mode.equals("persistent")&&epoch!=null){epoch.setProperty("mode","persistent");return;}
        if(epoch!=null){epoch.setProperty("closed",true);epoch.setProperty("endedSequence",sequence);}baseline(ns,actor,sequence,boot,timestamp);
    }
    public boolean tag(String ns,String id,String tag,String actor,ZonedDateTime time){revision(ns,id);Node old=core.find("RevisionTag",Map.of("namespaceId",ns,"revisionId",id));if(tag.equals(old==null?"":old.getProperty("tag")))return false;if(old!=null)delete(old);if(!tag.isEmpty()){Node n=node("RevisionTag",Json.map("id",UUID.randomUUID().toString(),"namespaceId",ns,"revisionId",id,"tag",tag,"updatedAt",time,"updatedBy",actor));core.entity("Revision",ns,id).createRelationshipTo(n,RelationshipType.withName("HAS_TAG"));}return true;}
    public static void delete(Node n){try(var edges=n.getRelationships()){for(var e:edges)e.delete();}n.delete();}
    public void deleteNamespace(String ns){for(String label:List.of("DepositSnapshot","RevisionTag","Revision","DepositHistoryEpoch"))for(Node n:core.nodes(label,Map.of("namespaceId",ns)))delete(n);}
    public void apply(String ns,String actor,UndoPlan plan){
        if(!plan.conflicts().isEmpty())throw ExceptionHandler.fail("REVISION_CONFLICT","History plan conflicts");var current=plan.changes().before;var target=plan.result();HistoryCodec.validate(target);validateResources(ns,target);
        Set<String> changed=plan.changes().touches.keySet();
        // Edges and owned values are removed before their schema/owners. Geometry lifecycle stays atomic.
        for(String kind:List.of("Relationship","ClassRelationship","Membership"))for(String id:changed){var m=current.get(id);if(m==null||!kind.equals(m.get("kind")))continue;Relationship edge=kind.equals("Membership")?core.membership(core.entity("Object",ns,(String)m.get("objectId"))):core.relationship(ns,kind,id);core.remove(edge,new ArrayList<>());}
        for(String id:changed){var m=current.get(id);if(m==null||!"Descriptor".equals(m.get("kind")))continue;var next=target.get(id);boolean remove=next==null||!Objects.equals(m.get("objectId"),next.get("objectId"))||!HistoryCodec.descriptorType(current,m).equals(HistoryCodec.descriptorType(target,next));if(remove)core.remove(core.entity("Descriptor",ns,id),new ArrayList<>());}
        for(String kind:List.of("ClassDescriptor","Object","Class","UserTool"))for(String id:changed){var m=current.get(id);if(m!=null&&kind.equals(m.get("kind"))&&!target.containsKey(id))core.remove(core.entity(kind,ns,id),new ArrayList<>());}
        // Vacate names before assigning a valid final permutation (e.g. two Classes swapped names).
        for(String id:changed){var old=current.get(id);var next=target.get(id);if(old!=null&&next!=null&&Set.of("Class","UserTool").contains((String)old.get("kind"))&&!Objects.equals(old.get("name"),next.get("name")))core.entity((String)old.get("kind"),ns,id).removeProperty("name");}
        for(String kind:List.of("Namespace","Class","ClassDescriptor","Object","UserTool"))for(String id:changed){var m=target.get(id);if(m==null||!kind.equals(m.get("kind")))continue;Node n=kind.equals("Namespace")?core.namespace(ns):core.entity(kind,ns,id);var props=properties(m);if(n==null){if(kind.equals("ClassDescriptor"))n=core.attachRestored(kind,core.entity("Class",ns,(String)m.get("classId")),actor,props,id);else n=core.createRestored(kind,ns,actor,props,id);}else core.update(n,props,actor);audit(n,m,actor);}
        for(String id:changed){var m=target.get(id);if(m!=null&&"Membership".equals(m.get("kind"))){var rel=core.assignRestored(core.entity("Object",ns,(String)m.get("objectId")),core.entity("Class",ns,(String)m.get("classId")),actor,id);audit(rel,m,actor);}}
        for(String id:changed){var m=target.get(id);if(m==null||!"Descriptor".equals(m.get("kind")))continue;Node n=core.entity("Descriptor",ns,id);if(n==null){n=core.attachRestored("Descriptor",core.entity("Object",ns,(String)m.get("objectId")),actor,Map.of("name",m.get("name")),id);}String type=HistoryCodec.descriptorType(target,m);if(type.equals("DGeometry")){new GeometryRepository(tx,core).writeRestored(n,ValueCodec.normalize(type,m.get("value")),actor,(String)m.get("geometryId"));core.update(n,Json.map("encoding","geometry","value",null),actor);}else core.writeValue(n,type,ValueCodec.normalize(type,m.get("value")),actor);if("DGeometry".equals(type)&&m.get("geometryId")!=null){Node g=new GeometryRepository(tx,core).geometry(n);audit(g,target.get(m.get("geometryId")),actor);}audit(n,m,actor);}
        for(String kind:List.of("ClassRelationship","Relationship"))for(String id:changed){var m=target.get(id);if(m==null||!kind.equals(m.get("kind")))continue;String endpoint=kind.equals("Relationship")?"Object":"Class";var rel=core.createRelationshipRestored(core.entity(endpoint,ns,(String)m.get("sourceId")),core.entity(endpoint,ns,(String)m.get("targetId")),(String)m.get("label"),properties(m),actor,id);audit(rel,m,actor);}
    }
    private Map<String,Object> properties(Map<String,Object> image){var props=new LinkedHashMap<String,Object>();for(String key:List.of("name","description","position","order","comment","type","data","weight"))if(image.containsKey(key))props.put(key,image.get(key));return props;}
    private void audit(Entity n,Map<String,Object> image,String actor){for(String key:List.of("createdAt","createdBy"))if(image.containsKey(key))n.setProperty(key,key.equals("createdAt")?ZonedDateTime.parse((String)image.get(key)):image.get(key));core.audit(n,actor);}
}
