package deposit.repository;

import deposit.common.*;
import java.util.*;
import java.time.*;
import org.neo4j.graphdb.*;

/** History-only persistence and portable live images, within the supplied action transaction. */
public final class HistoryRepository {
    @FunctionalInterface public interface HistoryWriteBudget {void reserveHistoryRecord(Map<String,Object> properties,int relationships);}
    private final Transaction tx;private final CoreRepository core;
    public HistoryRepository(Transaction tx,CoreRepository core){this.tx=tx;this.core=core;}
    private static Object portable(Object v){if(v instanceof java.time.temporal.TemporalAccessor)return v.toString();if(v instanceof double[] a)return Arrays.stream(a).boxed().toList();return v;}
    private Map<String,Object> image(Entity n,String kind,String... fields){var m=Json.map("kind",kind);for(String k:List.of("id","namespaceId","createdAt","createdBy","updatedAt","updatedBy"))if(n.hasProperty(k))m.put(k,portable(n.getProperty(k)));for(String k:fields)if(n.hasProperty(k))m.put(k,portable(n.getProperty(k)));return m;}
    public Map<String,Map<String,Object>> capture(String ns){
        return capture(ns,new TreeMap<>());
    }
    /** Stream images into the supplied state index, which may live entirely on disk. */
    public Map<String,Map<String,Object>> capture(String ns,Map<String,Map<String,Object>> out){
        core.observe("history-capture");
        captureImages(core.namespace(ns),out,null);
        for(String kind:List.of("Class","Object","ClassDescriptor","Descriptor","Pattern","UserTool"))
            try(var nodes=core.scanNodes(kind,Map.of("namespaceId",ns))) {
                while(nodes.hasNext())captureImages(nodes.next(),out,null);
            }
        for(String kind:List.of("Relationship","ClassRelationship")){String label=kind.equals("ClassRelationship")?"Class":"Object";try(ResourceIterator<Node> nodes=core.scanNodes(label,Map.of("namespaceId",ns))){while(nodes.hasNext())try(ResourceIterable<Relationship> edges=nodes.next().getRelationships(Direction.OUTGOING,RelationshipType.withName("RELATED_TO"))){for(Relationship edge:edges){if(kind.equals(core.kind(edge))&&ns.equals(edge.getEndNode().getProperty("namespaceId",null))){var m=core.data(edge);out.put(HistoryCodec.key(m),m);}}}}}
        return out;
    }
    void captureImages(Entity entity,ChangeSet.Images images) {
        captureImages(entity,images.state(),images);
    }

    private void captureImages(Entity entity,Map<String,Map<String,Object>> out,ChangeSet.Images affected) {
        if(entity instanceof Relationship edge) {
            var data=core.data(edge);out.put(HistoryCodec.key(data),data);return;
        }
        Node node=(Node)entity;
        String kind=CoreRepository.kind(node);
        var data=switch(kind) {
            case "Namespace" -> image(node,kind,"name","description");
            case "Class" -> image(node,kind,"name","position","order","comment");
            case "Object" -> image(node,kind,"position");
            case "ClassDescriptor" -> image(node,kind,"name","type","order","comment");
            case "Descriptor" -> image(node,kind,"name");
            case "Pattern" -> image(node,kind,"name","comment");
            case "UserTool" -> image(node,kind,"name","data","comment");
            default -> throw new IllegalStateException("Invalid scientific image: "+kind);
        };
        switch(kind) {
            case "Namespace" -> data.put("namespaceId",CoreRepository.id(node));
            case "Pattern" -> data.put("definition",Json.read((String)node.getProperty("definition")));
            case "ClassDescriptor" -> data.put("classId",node.getProperty("ownerId"));
            case "Descriptor" -> {
                var membership=core.membership(core.owner(node));
                Node clazz=membership.getEndNode();
                Node declaration=core.declaration(clazz,(String)data.get("name"));
                String type=(String)declaration.getProperty("type");
                Object value=core.descriptorValue(node,type);
                data.put("objectId",node.getProperty("ownerId"));data.put("value",value);
                if(affected!=null) {
                    String key=HistoryCodec.key(data);
                    affected.descriptorSchema().put(key,HistoryCodec.descriptorSchemaReferences(
                        CoreRepository.id(membership),CoreRepository.id(clazz),CoreRepository.id(declaration)));
                    if(value!=null&&type.equals("DResource"))
                        affected.resources().put(key,(String)ValueCodec.map(value).get("resourceId"));
                }
                var geometryEdge=node.getSingleRelationship(RelationshipType.withName("HAS_GEOMETRY"),Direction.OUTGOING);
                if(geometryEdge!=null) {
                    Node geometry=geometryEdge.getEndNode();
                    data.put("geometryId",CoreRepository.id(geometry));
                    var owned=image(geometry,"DGeometry","wkt","geometryType","srid","verticalSrid","axisOrder","dimensions");
                    owned.put("descriptorId",CoreRepository.id(node));out.put(HistoryCodec.key(owned),owned);
                }
            }
            case "Object" -> {
                var membership=core.membership(node);
                if(membership==null)throw new IllegalStateException("Object missing membership");
                captureImages(membership,out,null);
            }
            default -> {}
        }
        out.put(HistoryCodec.key(data),data);
    }

    public void refreshNamespaceImage(Node namespace,Map<String,Map<String,Object>> state) {
        captureImages(namespace,state,null);
    }

    private Node node(String label,Map<String,Object> props){Node n=tx.createNode(Label.label(label));props.forEach((k,v)->{if(v!=null)n.setProperty(k,v);});return n;}
    public Node epoch(String ns,String id){return core.entity("DepositHistoryEpoch",ns,id);}
    public boolean available(Node revision){Node e=epoch((String)revision.getProperty("namespaceId"),(String)revision.getProperty("historyEpochId"));return e!=null&&!Boolean.TRUE.equals(e.getProperty("expired",false));}
    public List<Revision> revisions(String ns){var out=new ArrayList<Revision>();for(Node n:core.nodes("Revision",Map.of("namespaceId",ns)))if(available(n))out.add(read(n));out.sort(Comparator.comparingLong(Revision::sequence).thenComparing(Revision::id));return out;}
    public Revision revision(String ns,String id){Node n=core.entity("Revision",ns,id);if(n==null||!available(n))throw ExceptionHandler.fail("NOT_FOUND","Revision not found");return read(n);}
    /** One revision envelope without materializing its change images. */
    public Map<String,Object> portableMetadata(Node n,ExportRecords records)throws java.io.IOException{
        var data=new LinkedHashMap<String,Object>();n.getAllProperties().forEach((key,value)->{if(!key.equals("bootId"))data.put(key,portable(value));});data.put("revisionId",CoreRepository.id(n));
        Node tag=core.find("RevisionTag",Map.of("namespaceId",n.getProperty("namespaceId"),"revisionId",CoreRepository.id(n)));data.put("tag",tag==null?null:tag.getProperty("tag"));
        Node epoch=epoch((String)n.getProperty("namespaceId"),(String)n.getProperty("historyEpochId"));if(epoch==null)HistoryCodec.invalid("Missing history epoch");
        data.put("epoch",Json.map("id",CoreRepository.id(epoch),"mode",epoch.getProperty("mode"),"closed",epoch.getProperty("closed",false),"baselineRevisionId",epoch.getProperty("baselineRevisionId")));
        Map<String,Object> touches=records.index();String dependencyCategory="history-journal-dependencies";int dependencyStart=records.list(dependencyCategory).size();
        Map<String,Object> scope=Map.of("ns",n.getProperty("namespaceId"),"id",CoreRepository.id(n));
        try(var rows=tx.execute("""
            MATCH (s:DepositSnapshot {namespaceId:$ns,revisionId:$id})
            WHERE s.entityKind IS NOT NULL AND s.entityId IS NOT NULL
            RETURN s ORDER BY s.entityKind,s.entityId
            """,scope)){
            while(rows.hasNext()){
                Node snapshot=(Node)rows.next().get("s");
                validateLink(n,snapshot,"HAS_CHANGE");
                String key=snapshotKey(n,snapshot);
                String[] fields=touchedFields(snapshot);
                if(fields!=null&&touches.put(key,List.of(fields))!=null)HistoryCodec.invalid("Duplicate snapshot identity");
            }
        }
        try(var rows=tx.execute("MATCH (d:RevisionDependency {namespaceId:$ns,revisionId:$id}) WHERE d.entityKey IS NOT NULL RETURN d ORDER BY d.entityKey",scope)){
            while(rows.hasNext()){
                Node dependency=(Node)rows.next().get("d");
                validateLink(n,dependency,"HAS_DEPENDENCY");
                records.addValue(dependencyCategory,dependencyKey(n,dependency));
            }
        }
        data.put("touches",touches);data.put("dependencies",records.valuesSlice(dependencyCategory,dependencyStart,records.list(dependencyCategory).size()));return data;
    }
    public Revision read(Node n){
        var data=new LinkedHashMap<String,Object>();n.getAllProperties().forEach((k,v)->data.put(k,portable(v)));data.put("revisionId",CoreRepository.id(n));
        var before=new TreeMap<String,Map<String,Object>>();var after=new TreeMap<String,Map<String,Object>>();var snapshotChanges=new ArrayList<Map<String,Object>>();
        var touches=new TreeMap<String,Set<String>>();var dependencies=new TreeSet<String>();var seen=new HashSet<String>();
        try(var edges=n.getRelationships(Direction.OUTGOING,RelationshipType.withName("HAS_CHANGE"))){
            for(Relationship edge:edges){
                Node snapshot=edge.getEndNode();
                String key=snapshotKey(n,snapshot);
                if(!seen.add(key))HistoryCodec.invalid("Duplicate snapshot identity");
                String[] fields=touchedFields(snapshot);
                if(fields!=null)touches.put(key,new TreeSet<>(List.of(fields)));
                if(Boolean.TRUE.equals(snapshot.getProperty("touchOnly",false)))continue;
                var prior=snapshotImage(snapshot,key,"before");var next=snapshotImage(snapshot,key,"after");
                if(prior!=null)before.put(key,prior);
                if(next!=null)after.put(key,next);
                snapshotChanges.add(Json.map("entityId",HistoryCodec.idFromKey(key),"entityKind",HistoryCodec.kindFromKey(key),"before",prior,"after",next));
            }
        }
        try(var edges=n.getRelationships(Direction.OUTGOING,RelationshipType.withName("HAS_DEPENDENCY"))){
            for(Relationship edge:edges)if(!dependencies.add(dependencyKey(n,edge.getEndNode())))HistoryCodec.invalid("Duplicate historical dependency");
        }
        Node tag=core.find("RevisionTag",Map.of("namespaceId",n.getProperty("namespaceId"),"revisionId",CoreRepository.id(n)));data.put("tag",tag==null?null:tag.getProperty("tag"));
        Node epoch=epoch((String)n.getProperty("namespaceId"),(String)n.getProperty("historyEpochId"));data.put("epoch",Json.map("id",CoreRepository.id(epoch),"mode",epoch.getProperty("mode"),"closed",epoch.getProperty("closed",false),"baselineRevisionId",epoch.getProperty("baselineRevisionId")));
        snapshotChanges.sort(Comparator.comparing(m->(String)m.get("entityId")));data.put("changes",snapshotChanges);var portableTouches=new TreeMap<String,Object>();touches.forEach((id,fields)->portableTouches.put(id,new ArrayList<>(fields)));data.put("touches",portableTouches);
        return new Revision(CoreRepository.id(n),((Number)n.getProperty("sequence")).longValue(),data,before,after,touches,dependencies);
    }
    public Revision append(String ns,String actor,String operation,long sequence,String kind,String summary,ChangeSet changes,String link,String target,ZonedDateTime timestamp){
        String id=appendId(ns,actor,operation,sequence,kind,summary,changes,link,target,timestamp,null);
        return read(core.entity("Revision",ns,id));
    }
    /** Import apply only needs the committed identity; do not reconstruct a mapping-sized Revision. */
    public String appendId(String ns,String actor,String operation,long sequence,String kind,String summary,ChangeSet changes,String link,String target,ZonedDateTime timestamp,HistoryWriteBudget budget){
        Node root=core.namespace(ns);String epoch=(String)root.getProperty("historyEpochId");String id=UUID.randomUUID().toString();
        Map<String,Object> properties=Json.map("id",id,"namespaceId",ns,"sequence",sequence,"historyEpochId",epoch,"historyMode",root.getProperty("historyMode"),"operationId",operation,"actorId",actor,"committedAt",timestamp,"kind",kind,"summary",summary,"parentRevisionId",kind.equals("baseline")?null:root.getProperty("headRevisionId",null));
        if(budget!=null)budget.reserveHistoryRecord(properties,1);Node revision=node("Revision",properties);
        if("transient".equals(root.getProperty("historyMode")))revision.setProperty("bootId",epoch(ns,epoch).getProperty("bootId"));
        if(link!=null)revision.setProperty(link,target);root.createRelationshipTo(revision,RelationshipType.withName("HAS_REVISION"));
        for(String dependency:changes.dependencies)writeDependency(revision,dependency,budget);
        for(var entry:changes.touches.entrySet()){
            String key=entry.getKey();
            var before=changes.before.get(key);var after=changes.after.get(key);
            // Preserve equal images when present. A journal entry without images has no entity effect.
            boolean touchOnly=before==null&&after==null;
            String priorResource=changes.retainedResource(before,true),nextResource=changes.retainedResource(after,false);
            int retains=(priorResource==null?0:1)+(nextResource==null||nextResource.equals(priorResource)?0:1);
            Node snapshot=writeSnapshot(revision,key,entry.getValue(),before,after,touchOnly,retains,budget);
            retain(ns,snapshot,priorResource);retain(ns,snapshot,nextResource);
        }
        root.setProperty("headRevisionId",id);
        return id;
    }
    private static Map<String,Object> ownedRecord(Node revision){
        return Json.map("id",UUID.randomUUID().toString(),"namespaceId",revision.getProperty("namespaceId"),
            "revisionId",CoreRepository.id(revision),"historyEpochId",revision.getProperty("historyEpochId"));
    }

    /** Package clone and local actions write the same snapshot and journal representation. */
    public Node writeSnapshot(Node revision,String key,Collection<?> fields,Map<String,Object> before,
                              Map<String,Object> after,boolean touchOnly,int retains,HistoryWriteBudget budget){
        validateKey(key);
        var properties=ownedRecord(revision);
        properties.put("entityKind",HistoryCodec.kindFromKey(key));properties.put("entityId",HistoryCodec.idFromKey(key));
        if(fields!=null){
            var sorted=new TreeSet<String>();
            for(Object field:fields){
                if(!(field instanceof String)||((String)field).isEmpty())HistoryCodec.invalid("Invalid historical touch field");
                sorted.add((String)field);
            }
            properties.put("touchedFields",sorted.toArray(String[]::new));
        }
        if(touchOnly){
            if(fields==null||before!=null||after!=null||retains!=0)HistoryCodec.invalid("Invalid touch-only snapshot");
            properties.put("touchOnly",true);
        }
        for(var side:List.of("before","after")){
            var image=side.equals("before")?before:after;
            properties.put(side+"Exists",image!=null);
            if(image!=null){
                validateImage(key,image);
                properties.put(side+"Json",Json.write(image));
            }
        }
        if(budget!=null)budget.reserveHistoryRecord(properties,1+retains);
        Node snapshot=node("DepositSnapshot",properties);
        revision.createRelationshipTo(snapshot,RelationshipType.withName("HAS_CHANGE"));
        return snapshot;
    }

    /**
     * Immutable revision and epoch metadata. The record has id, namespaceId, revisionId,
     * historyEpochId, and an entityKey encoding the logical kind and UUID.
     */
    public void writeDependency(Node revision,String key,HistoryWriteBudget budget){
        validateKey(key);
        var properties=ownedRecord(revision);
        properties.put("entityKey",key);
        if(budget!=null)budget.reserveHistoryRecord(properties,1);
        revision.createRelationshipTo(node("RevisionDependency",properties),RelationshipType.withName("HAS_DEPENDENCY"));
    }

    private static void validateKey(String key){
        if(!HistoryCodec.KINDS.contains(HistoryCodec.kindFromKey(key)))HistoryCodec.invalid("Invalid history identity category");
        String id=HistoryCodec.idFromKey(key);
        try{if(!id.equals(UUID.fromString(id).toString()))HistoryCodec.invalid("Invalid history identity");}
        catch(IllegalArgumentException invalid){HistoryCodec.invalid("Invalid history identity");}
    }
    private static void validateLink(Node revision,Node record,String type){
        var link=record.getSingleRelationship(RelationshipType.withName(type),Direction.INCOMING);
        if(link==null||!revision.equals(link.getStartNode()))HistoryCodec.invalid("Missing history record owner");
    }
    private static void validateOwner(Node revision,Node record){
        if(!CoreRepository.id(revision).equals(record.getProperty("revisionId",null))
            ||!revision.getProperty("namespaceId").equals(record.getProperty("namespaceId",null))
            ||!revision.getProperty("historyEpochId").equals(record.getProperty("historyEpochId",null)))
            HistoryCodec.invalid("Inconsistent history record ownership");
    }
    private static void validateImage(String key,Map<String,Object> image){
        if(!HistoryCodec.kindFromKey(key).equals(image.get("kind"))||!HistoryCodec.idFromKey(key).equals(image.get("id")))
            HistoryCodec.invalid("Inconsistent snapshot image identity");
    }
    private static String dependencyKey(Node revision,Node dependency){
        validateOwner(revision,dependency);
        String key=(String)dependency.getProperty("entityKey");validateKey(key);return key;
    }
    public static String snapshotKey(Node revision,Node snapshot){
        validateOwner(revision,snapshot);
        String key=HistoryCodec.key((String)snapshot.getProperty("entityKind"),(String)snapshot.getProperty("entityId"));
        validateKey(key);
        Object touchOnly=snapshot.getProperty("touchOnly",false);
        if(!(touchOnly instanceof Boolean))HistoryCodec.invalid("Invalid touch-only marker");
        for(String side:List.of("before","after")){
            Object exists=snapshot.getProperty(side+"Exists",null);
            if(!(exists instanceof Boolean)||Boolean.TRUE.equals(exists)!=snapshot.hasProperty(side+"Json")
                ||Boolean.TRUE.equals(touchOnly)&&Boolean.TRUE.equals(exists))HistoryCodec.invalid("Invalid snapshot image flags");
        }
        if(Boolean.TRUE.equals(touchOnly)&&(!snapshot.hasProperty("touchedFields")||snapshot.hasRelationship(Direction.OUTGOING,RelationshipType.withName("RETAINS_RESOURCE"))))
            HistoryCodec.invalid("Invalid touch-only snapshot");
        return key;
    }
    private static String[] touchedFields(Node snapshot){
        Object fields=snapshot.getProperty("touchedFields",null);
        if(fields==null)return null;
        if(!(fields instanceof String[]))HistoryCodec.invalid("Invalid snapshot touch fields");
        String[] array=(String[])fields;
        String previous=null;
        for(String field:array){
            if(field.isEmpty()||previous!=null&&previous.compareTo(field)>=0)HistoryCodec.invalid("Noncanonical snapshot touch fields");
            previous=field;
        }
        return array;
    }
    private static Map<String,Object> snapshotImage(Node snapshot,String key,String side){
        if(!Boolean.TRUE.equals(snapshot.getProperty(side+"Exists")))return null;
        Map<String,Object> image;
        try{image=Json.read((String)snapshot.getProperty(side+"Json"));}
        catch(RuntimeException failure){throw ExceptionHandler.fail("REVISION_CONFLICT","Invalid snapshot JSON");}
        validateImage(key,image);return image;
    }

    private void retain(String ns,Node snapshot,String id){
        if(id==null)return;
        Node resource=validateResource(ns,id);
        try(var links=snapshot.getRelationships(Direction.OUTGOING,RelationshipType.withName("RETAINS_RESOURCE"))){for(var link:links)if(link.getEndNode().equals(resource))return;}
        snapshot.createRelationshipTo(resource,RelationshipType.withName("RETAINS_RESOURCE"));new ResourceRepository(tx,core).changed(resource);
    }
    public Node validateResource(String ns,String id){
        var resources=new ResourceRepository(tx,core);
        resources.lock(id);Node resource=resources.resource(ns,id);
        resources.verifyCompleted(resource);return resource;
    }
    public void validateResources(String ns,Map<String,Map<String,Object>> state){for(var m:state.values())if("Descriptor".equals(m.get("kind"))&&m.get("value")!=null&&"DResource".equals(HistoryCodec.descriptorType(state,m)))validateResource(ns,(String)ValueCodec.map(m.get("value")).get("resourceId"));var namespace=HistoryCodec.find(state,ns,"Namespace");Node other=core.find("Namespace","name",namespace.get("name"));if(other!=null&&!CoreRepository.id(other).equals(ns))HistoryCodec.invalid("Namespace name is now occupied");}
    public void baseline(String ns,String actor,long sequence,String boot,ZonedDateTime timestamp){
        baseline(ns,actor,sequence,boot,timestamp,null,null);
    }
    public void baseline(String ns,String actor,long sequence,String boot,ZonedDateTime timestamp,HistoryWorkspace workspace,HistoryWriteBudget budget){
        Node root=core.namespace(ns);if("disabled".equals(root.getProperty("historyMode"))){root.removeProperty("headRevisionId");root.removeProperty("historyEpochId");return;}
        String epochId=UUID.randomUUID().toString();Map<String,Object> properties=Json.map("id",epochId,"namespaceId",ns,"mode",root.getProperty("historyMode"),"bootId",boot,"closed",false,"expired",false,"startedSequence",sequence,"baselineRevisionId","pending");if(budget!=null)budget.reserveHistoryRecord(properties,1);Node epoch=node("DepositHistoryEpoch",properties);root.setProperty("historyEpochId",epochId);root.createRelationshipTo(epoch,RelationshipType.withName("HAS_HISTORY_EPOCH"));
        var changes=workspace==null?new ChangeSet(Map.of()):workspace.journal(workspace.newState());changes.checkpoint(workspace==null?capture(ns):capture(ns,workspace.newState()));String baseline=appendId(ns,actor,null,sequence,"baseline","Epoch baseline",changes,null,null,timestamp,budget);epoch.setProperty("baselineRevisionId",baseline);
    }
    public void mode(String ns,String mode,String actor,long sequence,String boot,ZonedDateTime timestamp){
        Node root=core.namespace(ns);String old=(String)root.getProperty("historyMode");Node epoch=root.hasProperty("historyEpochId")?epoch(ns,(String)root.getProperty("historyEpochId")):null;
        root.setProperty("historyMode",mode);
        if(old.equals("transient")&&mode.equals("persistent")&&epoch!=null){epoch.setProperty("mode","persistent");return;}
        if(epoch!=null){epoch.setProperty("closed",true);epoch.setProperty("endedSequence",sequence);}baseline(ns,actor,sequence,boot,timestamp);
    }
    public boolean tag(String ns,String id,String tag,String actor,ZonedDateTime time){Revision selected=revision(ns,id);Node old=core.find("RevisionTag",Map.of("namespaceId",ns,"revisionId",id));if(tag.equals(old==null?"":old.getProperty("tag")))return false;if(old!=null)delete(old);if(!tag.isEmpty()){Node n=node("RevisionTag",Json.map("id",UUID.randomUUID().toString(),"namespaceId",ns,"revisionId",id,"historyEpochId",selected.data().get("historyEpochId"),"tag",tag,"updatedAt",time,"updatedBy",actor));core.entity("Revision",ns,id).createRelationshipTo(n,RelationshipType.withName("HAS_TAG"));}return true;}
    public static void delete(Node n){HistoryRecordDeletion.delete(n);}
    public void deleteNamespace(String ns){new HistoryRecordDeletion(tx).deleteNamespace(ns);}
    public void apply(String ns,String actor,UndoPlan plan){
        if(!plan.conflicts().isEmpty())throw ExceptionHandler.fail("REVISION_CONFLICT","History plan conflicts");var current=plan.changes().before;var target=plan.result();HistoryCodec.validate(target);validateResources(ns,target);
        Set<String> changed=plan.changes().touches.keySet();
        // Edges and owned values are removed before their schema/owners. Geometry lifecycle stays atomic.
        for(String kind:List.of("Relationship","ClassRelationship","Membership"))for(String key:changed){var m=current.get(key);if(m==null||!kind.equals(m.get("kind")))continue;Relationship edge=kind.equals("Membership")?core.membership(core.entity("Object",ns,(String)m.get("objectId"))):core.readRelationship(ns,kind,(String)m.get("id"));if(edge!=null)core.remove(edge,new ArrayList<>());}
        for(String key:changed){var m=current.get(key);if(m==null||!"Descriptor".equals(m.get("kind")))continue;var next=target.get(key);boolean remove=next==null||!Objects.equals(m.get("objectId"),next.get("objectId"))||!HistoryCodec.descriptorType(current,m).equals(HistoryCodec.descriptorType(target,next));if(remove)core.remove(core.entity("Descriptor",ns,(String)m.get("id")),new ArrayList<>());}
        for(String kind:List.of("ClassDescriptor","Object","Pattern","Class","UserTool"))for(String key:changed){var m=current.get(key);if(m!=null&&kind.equals(m.get("kind"))&&!target.containsKey(key))core.remove(core.entity(kind,ns,(String)m.get("id")),new ArrayList<>());}
        // Vacate names before assigning a valid final permutation (e.g. two Classes swapped names).
        for(String key:changed){var old=current.get(key);var next=target.get(key);if(old!=null&&next!=null&&Set.of("Class","Pattern","UserTool").contains((String)old.get("kind"))&&!Objects.equals(old.get("name"),next.get("name")))core.entity((String)old.get("kind"),ns,(String)old.get("id")).removeProperty("name");}
        for(String kind:List.of("Namespace","Class","ClassDescriptor","Object","Pattern","UserTool"))for(String key:changed){var m=target.get(key);if(m==null||!kind.equals(m.get("kind")))continue;String id=(String)m.get("id");Node n=kind.equals("Namespace")?core.namespace(ns):core.entity(kind,ns,id);var props=properties(m);if(n==null){if(kind.equals("ClassDescriptor"))n=core.attachRestored(kind,core.entity("Class",ns,(String)m.get("classId")),actor,props,id);else n=core.createRestored(kind,ns,actor,props,id);}else core.update(n,props,actor);audit(n,m,actor);}
        for(String key:changed){var m=target.get(key);if(m!=null&&"Membership".equals(m.get("kind"))){var rel=core.assignRestored(core.entity("Object",ns,(String)m.get("objectId")),core.entity("Class",ns,(String)m.get("classId")),actor,(String)m.get("id"));audit(rel,m,actor);}}
        for(String key:changed){var m=target.get(key);if(m==null||!"Descriptor".equals(m.get("kind")))continue;String id=(String)m.get("id");Node n=core.entity("Descriptor",ns,id);if(n==null){n=core.attachRestored("Descriptor",core.entity("Object",ns,(String)m.get("objectId")),actor,Map.of("name",m.get("name")),id);}String type=HistoryCodec.descriptorType(target,m);if(type.equals("DGeometry")){new GeometryRepository(tx,core).writeRestored(n,ValueCodec.normalize(type,m.get("value")),actor,(String)m.get("geometryId"));core.update(n,Json.map("encoding","geometry","value",null),actor);}else core.writeValue(n,type,ValueCodec.normalize(type,m.get("value")),actor);if("DGeometry".equals(type)&&m.get("geometryId")!=null){Node g=new GeometryRepository(tx,core).geometry(n);audit(g,HistoryCodec.find(target,m.get("geometryId"),"DGeometry"),actor);}audit(n,m,actor);}
        for(String kind:List.of("ClassRelationship","Relationship"))for(String key:changed){var m=target.get(key);if(m==null||!kind.equals(m.get("kind")))continue;String id=(String)m.get("id"),endpoint=kind.equals("Relationship")?"Object":"Class";var rel=core.createRelationshipRestored(core.entity(endpoint,ns,(String)m.get("sourceId")),core.entity(endpoint,ns,(String)m.get("targetId")),(String)m.get("label"),properties(m),actor,id);audit(rel,m,actor);}
    }
    private Map<String,Object> properties(Map<String,Object> image){var props=new LinkedHashMap<String,Object>();for(String key:List.of("name","description","position","order","comment","type","data","weight"))if(image.containsKey(key))props.put(key,image.get(key));if("Pattern".equals(image.get("kind")))props.put("definition",Json.write(image.get("definition")));return props;}
    private void audit(Entity n,Map<String,Object> image,String actor){for(String key:List.of("createdAt","createdBy"))if(image.containsKey(key))n.setProperty(key,key.equals("createdAt")?ZonedDateTime.parse((String)image.get(key)):image.get(key));core.audit(n,actor);}
}
