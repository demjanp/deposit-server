package deposit.repository;

import deposit.common.*;
import deposit.common.Results.Outcome;
import java.time.*;
import java.util.*;
import org.neo4j.graphdb.*;

/** Graph storage only. Authorization and action boundaries belong to services. */
public final class CoreRepository implements CoreProjectionRepository.Access, OperationReceiptRepository.Access {
    private final Transaction tx;
    private final ZonedDateTime timestamp;
    private final CoreProjectionRepository projections;
    private final OperationReceiptRepository receipts;
    public CoreRepository(Transaction tx,ZonedDateTime timestamp) {this.tx=tx;this.timestamp=timestamp;projections=new CoreProjectionRepository(this);receipts=new OperationReceiptRepository(tx,this);}
    public Node find(String label,String property,Object value) {return tx.findNode(Label.label(label),property,value);}
    public Node find(String label,Map<String,Object> properties) {
        try(var nodes=tx.findNodes(Label.label(label),properties)) {return nodes.hasNext()?nodes.next():null;}
    }
    public Node entity(String label,String namespace,String id) {return find(label,Map.of("namespaceId",namespace,"id",id));}
    public ResourceIterator<Node> scanNodes(String label,Map<String,Object> properties){return properties.isEmpty()?tx.findNodes(Label.label(label)):tx.findNodes(Label.label(label),properties);}
    public List<Node> nodes(String label,Map<String,Object> properties) {
        try(var nodes=properties.isEmpty()?tx.findNodes(Label.label(label)):tx.findNodes(Label.label(label),properties)) {return nodes.stream().sorted(Comparator.comparing(n->(String)n.getProperty("id",""))).toList();}
    }
    public static String id(Entity n) {return (String)n.getProperty("id");}
    public static String kind(Node node) {
        for(String label:List.of("Namespace","User","Class","Object","ClassDescriptor","Descriptor","Pattern","UserTool","DGeometry","DResource")) if(node.hasLabel(Label.label(label))) return label;
        throw new IllegalStateException("Unknown domain label");
    }
    public Node namespace(String id) {return find("Namespace","id",id);}
    public long sequence(String id) {Node ns=namespace(id);if(ns==null) throw ExceptionHandler.fail("NOT_FOUND","Namespace not found");return ((Number)ns.getProperty("sequence")).longValue();}
    public void lockNamespace(String id) {Node lock=find("DepositNamespaceLock","id",id);if(lock==null)throw new IllegalStateException("Namespace coordination missing");tx.acquireWriteLock(lock);}
    public void lockActor(String id) {tx.acquireWriteLock(find("DepositActorLock","id",id));}
    public String role(String actor,String namespace) {
        Node user=find("User","id",actor);if(user==null) return null;
        try(var edges=user.getRelationships(Direction.OUTGOING,RelationshipType.withName("HAS_NAMESPACE_ROLE"))) {
            for(Relationship edge:edges) if(namespace.equals(edge.getEndNode().getProperty("id",null))) return (String)edge.getProperty("role");
        }return null;
    }
    public boolean setRole(String actor,String namespace,String role) {
        Node user=find("User","id",actor);Node ns=namespace(namespace);
        try(var edges=user.getRelationships(Direction.OUTGOING,RelationshipType.withName("HAS_NAMESPACE_ROLE"))) {
            for(Relationship edge:edges) if(edge.getEndNode().equals(ns)) {
                if(Objects.equals(edge.getProperty("role"),role)) return false;
                if(role==null) edge.delete();else edge.setProperty("role",role);return true;
            }
        }
        if(role==null) return false;
        user.createRelationshipTo(ns,RelationshipType.withName("HAS_NAMESPACE_ROLE")).setProperty("role",role);return true;
    }
    public List<Map<String,Object>> roles(String namespace) {
        var result=new ArrayList<Map<String,Object>>();
        try(var edges=namespace(namespace).getRelationships(Direction.INCOMING,RelationshipType.withName("HAS_NAMESPACE_ROLE"))) {
            for(Relationship edge:edges) result.add(Json.map("userId",id(edge.getStartNode()),"role",edge.getProperty("role")));
        }
        result.sort(Comparator.comparing(m->(String)m.get("userId")));return result;
    }
    public Node create(String label,String namespace,String actor,Map<String,Object> properties) {
        return createRestored(label,namespace,actor,properties,UUID.randomUUID().toString());
    }
    public Node createRestored(String label,String namespace,String actor,Map<String,Object> properties,String restoredId) {
        Node n=namespace==null?tx.createNode(Label.label(label)):tx.createNode(Label.label(label),Label.label("DepositLive"));
        n.setProperty("id",restoredId);
        if(label.equals("Namespace"))tx.createNode(Label.label("DepositNamespaceLock")).setProperty("id",id(n));
        if(namespace!=null) n.setProperty("namespaceId",namespace);
        var now=timestamp;n.setProperty("createdAt",now);n.setProperty("createdBy",actor==null?id(n):actor);
        n.setProperty("updatedAt",now);n.setProperty("updatedBy",actor==null?id(n):actor);
        properties.forEach((k,v)->{if(v!=null)n.setProperty(k,property(v));});
        if(label.equals("User"))tx.createNode(Label.label("DepositActorLock")).setProperty("id",id(n));
        if(namespace!=null && Set.of("Class","Object","Pattern","UserTool").contains(label)) namespace(namespace).createRelationshipTo(n,RelationshipType.withName("CONTAINS"));
        return n;
    }
    private static Object property(Object v) {if(v instanceof List<?> list) return list.stream().mapToDouble(x->((Number)x).doubleValue()).toArray();return v;}
    public boolean update(Entity node,Map<String,Object> values,String actor) {
        boolean changed=false;
        for(var entry:values.entrySet()) {
            Object value=entry.getValue()==null?null:property(entry.getValue());Object old=node.getProperty(entry.getKey(),null);
            if(!Objects.deepEquals(old,value)) {changed=true;if(value==null)node.removeProperty(entry.getKey());else node.setProperty(entry.getKey(),value);}
        }
        if(changed) audit(node,actor);return changed;
    }
    public void audit(Entity node,String actor) {node.setProperty("updatedAt",timestamp);node.setProperty("updatedBy",actor);}
    public void advanceNamespace(String namespace,String actor) {Node ns=namespace(namespace);ns.setProperty("sequence",sequence(namespace)+1);audit(ns,actor);}
    public Relationship membership(Node object) {return object.getSingleRelationship(RelationshipType.withName("MEMBER_OF"),Direction.OUTGOING);}
    public Node objectClass(Node object) {var membership=membership(object);if(membership==null) throw new IllegalStateException("Object has no Class");return membership.getEndNode();}
    public Relationship assign(Node object,Node clazz,String actor) {
        return assignRestored(object,clazz,actor,null);
    }
    public Relationship assignRestored(Node object,Node clazz,String actor,String restoredId) {
        Relationship existing=membership(object);
        if(existing!=null && existing.getEndNode().equals(clazz)) return existing;
        String id=restoredId!=null?restoredId:existing==null?UUID.randomUUID().toString():id(existing);
        var properties=existing==null?new HashMap<String,Object>():new HashMap<>(existing.getAllProperties());
        if(existing!=null)existing.delete();
        String key=object.getProperty("namespaceId")+"/Membership/"+id(object);
        if(find("DepositEdgeIdentity","key",key)==null) {Node registry=tx.createNode(Label.label("DepositEdgeIdentity"));registry.setProperty("key",key);registry.setProperty("id",id);}
        Relationship rel=object.createRelationshipTo(clazz,RelationshipType.withName("MEMBER_OF"));properties.forEach(rel::setProperty);
        rel.setProperty("id",id);rel.setProperty("namespaceId",object.getProperty("namespaceId"));
        if(existing==null) {rel.setProperty("createdAt",timestamp);rel.setProperty("createdBy",actor);}audit(rel,actor);return rel;
    }
    public Node declaration(Node clazz,String name) {return find("ClassDescriptor",Map.of("namespaceId",clazz.getProperty("namespaceId"),"ownerId",id(clazz),"name",name));}
    public Node descriptor(Node object,String name) {return find("Descriptor",Map.of("namespaceId",object.getProperty("namespaceId"),"ownerId",id(object),"name",name));}
    public List<Node> children(Node owner,String label) {
        return nodes(label,Map.of("namespaceId",owner.getProperty("namespaceId"),"ownerId",id(owner)));
    }
    public Node attach(String label,Node owner,String actor,Map<String,Object> props) {
        return attachRestored(label,owner,actor,props,UUID.randomUUID().toString());
    }
    public Node attachRestored(String label,Node owner,String actor,Map<String,Object> props,String restoredId) {
        var fields=new LinkedHashMap<>(props);fields.put("ownerId",id(owner));
        Node n=createRestored(label,(String)owner.getProperty("namespaceId"),actor,fields,restoredId);owner.createRelationshipTo(n,RelationshipType.withName("HAS_DESCRIPTOR"));return n;
    }
    public List<Node> members(Node clazz) {
        var result=new ArrayList<Node>();try(var edges=clazz.getRelationships(Direction.INCOMING,RelationshipType.withName("MEMBER_OF"))) {for(var edge:edges) result.add(edge.getStartNode());}
        result.sort(Comparator.comparing(CoreRepository::id));return result;
    }
    public Node owner(Node descriptor) {return descriptor.getSingleRelationship(RelationshipType.withName("HAS_DESCRIPTOR"),Direction.INCOMING).getStartNode();}
    public String descriptorType(Node descriptor) {return (String)declaration(objectClass(owner(descriptor)),(String)descriptor.getProperty("name")).getProperty("type");}
    private GeometryRepository geometry(){return new GeometryRepository(tx,this);}
    @Override public Node geometry(Node descriptor) {return geometry().geometry(descriptor);}
    private ResourceRepository resources(){return new ResourceRepository(tx,this);}
    public Object descriptorValue(Node descriptor) {
        String type=descriptorType(descriptor);
        if(type.equals("DGeometry")){var g=geometry();Node node=g.geometry(descriptor);return node==null?null:g.value(node);}
        if(type.equals("DResource")){Node node=resources().linked(descriptor);return node==null?null:Json.map("resourceId",id(node));}
        return ValueCodec.decode(type,descriptor.getProperty("value",null));
    }
    public boolean writeValue(Node descriptor,String type,Object value,String actor) {
        boolean changed=false;String encoding=switch(type){case "DGeometry"->"geometry";case "DResource"->"resource";case "List","DDict"->"json";default->"native";};
        if(type.equals("DGeometry"))changed=geometry().write(descriptor,value,actor);
        else if(type.equals("DResource")){
            changed=resources().write(descriptor,value,actor,resourceWriteRole(actor,(String)descriptor.getProperty("namespaceId")));
        }
        Object stored=Set.of("DGeometry","DResource").contains(type)?null:ValueCodec.storage(type,value);
        changed=update(descriptor,Json.map("encoding",encoding,"value",stored),actor)||changed;if(changed)audit(descriptor,actor);return changed;
    }
    String resourceWriteRole(String actor,String namespace) {
        var internal=(org.neo4j.kernel.impl.coreapi.InternalTransaction)tx;var server=ServerContext.forDatabase(internal.getDatabaseId());
        Node user=find("User","id",actor);String role=role(actor,namespace);
        if(user!=null&&server.state(internal.kernelTransaction()).administrative&&"administrator".equals(user.getProperty("serverRole",null)))role="owner";
        return role;
    }
    /** Materialization-only resource attachment; ordinary public value authorization is unchanged. */
    public boolean writeImportedValue(Node descriptor,String type,Object value,String actor,ResourceRepository.ImportedResources imported) {
        if(!"DResource".equals(type)||value==null)return writeValue(descriptor,type,value,actor);
        boolean changed=resources().writeImported(descriptor,value,actor,imported);
        changed=update(descriptor,Json.map("encoding","resource","value",null),actor)||changed;
        if(changed)audit(descriptor,actor);return changed;
    }
    private void removeValue(Node descriptor,List<String> deleted) {
        var edge=descriptor.getSingleRelationship(RelationshipType.withName("HAS_GEOMETRY"),Direction.OUTGOING);
        if(edge!=null){deleted.add(id(edge.getEndNode()));geometry().write(descriptor,null,(String)descriptor.getProperty("updatedBy"));}
        var resourceEdge=descriptor.getSingleRelationship(RelationshipType.withName("HAS_RESOURCE"),Direction.OUTGOING);
        if(resourceEdge!=null){var r=resources();Node resource=resourceEdge.getEndNode();r.lock(id(resource));resourceEdge.delete();r.changed(resource);}
    }
    public Map<String,Object> data(Node node) {return projections.data(node);}
    public Map<String,Object> data(Node node,boolean includeOwnedValues) {return projections.data(node,includeOwnedValues);}
    public static String kind(Relationship rel) {
        String source=kind(rel.getStartNode()),target=kind(rel.getEndNode());
        if(rel.isType(RelationshipType.withName("MEMBER_OF"))&&source.equals("Object")&&target.equals("Class"))return "Membership";
        if(rel.isType(RelationshipType.withName("RELATED_TO"))&&source.equals(target)) {
            if(source.equals("Object"))return "Relationship";
            if(source.equals("Class"))return "ClassRelationship";
        }
        throw new IllegalStateException("Invalid domain edge endpoints");
    }
    public Map<String,Object> data(Relationship rel) {return projections.data(rel);}
    private String edgeKey(Relationship rel) {
        String kind=kind(rel),ns=(String)rel.getProperty("namespaceId");
        return kind.equals("Membership")?ns+"/Membership/"+id(rel.getStartNode()):relationshipKey(ns,kind,id(rel.getStartNode()),id(rel.getEndNode()),(String)rel.getProperty("label"));
    }
    private String relationshipKey(String ns,String kind,String source,String target,String label) {
        return ns+"/"+kind+"/"+Json.write(List.of(source,target,label));
    }
    public List<Relationship> relationships(String namespace,String category) {
        var results=new ArrayList<Relationship>();String label=category.equals("ClassRelationship")?"Class":"Object";
        for(Node source:nodes(label,Map.of("namespaceId",namespace))) {
            try(var edges=source.getRelationships(Direction.OUTGOING,RelationshipType.withName("RELATED_TO"))) {
                for(var edge:edges) {if(!category.equals(kind(edge))||!namespace.equals(edge.getEndNode().getProperty("namespaceId",null)))throw new IllegalStateException("Invalid scoped edge");results.add(edge);}
            }
        }
        results.sort(Comparator.comparing(CoreRepository::id));return results;
    }
    public Relationship relationship(String ns,String category,String id) {
        for(var rel:relationships(ns,category))if(id.equals(id(rel)))return rel;return null;
    }
    public Relationship relationship(Node source,Node target,String label) {
        try(var edges=source.getRelationships(Direction.OUTGOING,RelationshipType.withName("RELATED_TO"))) {
            for(var rel:edges)if(rel.getEndNode().equals(target)&&label.equals(rel.getProperty("label")))return rel;
        }return null;
    }
    public Relationship createRelationship(Node source,Node target,String label,Map<String,Object> properties,String actor) {
        return createRelationshipRestored(source,target,label,properties,actor,UUID.randomUUID().toString());
    }
    public Relationship createRelationshipRestored(Node source,Node target,String label,Map<String,Object> properties,String actor,String restoredId) {
        Relationship rel=source.createRelationshipTo(target,RelationshipType.withName("RELATED_TO"));
        rel.setProperty("id",restoredId);rel.setProperty("namespaceId",source.getProperty("namespaceId"));rel.setProperty("label",label);
        rel.setProperty("createdAt",timestamp);rel.setProperty("createdBy",actor);audit(rel,actor);
        properties.forEach((key,value)->{if(value!=null)rel.setProperty(key,value);});
        Node registry=tx.createNode(Label.label("DepositEdgeIdentity"));registry.setProperty("key",edgeKey(rel));registry.setProperty("id",id(rel));return rel;
    }
    public void remove(Relationship rel,List<String> deleted) {
        Node registry=find("DepositEdgeIdentity","key",edgeKey(rel));
        if(registry==null||!id(rel).equals(registry.getProperty("id")))throw new IllegalStateException("Missing edge identity");
        registry.delete();deleted.add(id(rel));rel.delete();
    }
    /** Restoration only: preserve the portable edge identity and its uniqueness registry together. */
    public void restoreEdgeId(Relationship rel,String id) {
        Node registry=find("DepositEdgeIdentity","key",edgeKey(rel));
        if(registry==null)throw new IllegalStateException("Missing restored edge registry");
        registry.setProperty("id",id);rel.setProperty("id",id);
    }
    public void remove(Node node,List<String> deleted) {
        deleted.add(id(node));
        if(kind(node).equals("Descriptor"))removeValue(node,deleted);
        if(kind(node).equals("Object")) {
            for(Node d:children(node,"Descriptor"))remove(d,deleted);
        }
        if(kind(node).equals("Class")) for(Node d:children(node,"ClassDescriptor"))remove(d,deleted);
        try(var edges=node.getRelationships()) {for(var rel:edges) {if(rel.hasProperty("id"))remove(rel,deleted);else rel.delete();}}
        node.delete();
    }
    public Node receipt(String scope,String operation) {return receipts.receipt(scope,operation);}
    public Node binding(String actor,String operation) {return receipts.binding(actor,operation);}
    public void saveReceipt(RequestContext c,String scope,String hash,Outcome outcome) {receipts.saveReceipt(c,scope,hash,outcome);}
    public Outcome replay(Node receipt,String scope) {return receipts.replay(receipt,scope);}
    public boolean bootstrapped() {return find("DepositInitialization","id","bootstrap")!=null || nodes("User",Map.of("serverRole","administrator")).size()>0;}
    public void validateOwnerDeactivation(String actor) {
        for(Node ns:nodes("Namespace",Map.of())) {
            String namespace=id(ns);
            if(!"owner".equals(role(actor,namespace)))continue;
            boolean other=roles(namespace).stream().anyMatch(r->"owner".equals(r.get("role"))&&!actor.equals(r.get("userId"))&&Boolean.TRUE.equals(find("User","id",r.get("userId")).getProperty("enabled",false)));
            if(!other)throw ExceptionHandler.fail("VALIDATION_FAILED","Reassign Namespace ownership before disabling this account");
        }
    }
    public List<String> deleteNamespace(String namespace) {
        var deleted=new TreeSet<String>();
        new HistoryRecordDeletion(tx).deleteNamespace(namespace);
        // Remove owned values while ownership/declarations still exist; operational records survive.
        for(Node descriptor:nodes("Descriptor",Map.of("namespaceId",namespace))) {var ids=new ArrayList<String>();removeValue(descriptor,ids);deleted.addAll(ids);}
        geometry().deleteLayers(namespace);
        for(Node loc:nodes("DepositResourceLocation",Map.of("namespaceId",namespace))) {loc.setProperty("leaseUntil",0L);loc.setProperty("everAttached",true);resources().orphan(loc);}
        for(Node grant:nodes("DepositExportGrant",Map.of("namespaceId",namespace)))grant.setProperty("expires",0L);
        // Receipts and operation bindings intentionally survive deletion, without native links.
        for(String label:List.of("Object","Descriptor","ClassDescriptor","Class","Pattern","UserTool")) {
            for(Node node:nodes(label,Map.of("namespaceId",namespace))) {
                deleted.add(id(node));
                try(var edges=node.getRelationships()) {for(Relationship edge:edges) {if(edge.hasProperty("id"))deleted.add(id(edge));edge.delete();}}
                node.delete();
            }
        }
        try(var registry=tx.findNodes(Label.label("DepositEdgeIdentity"))) {
            for(Node node:registry.stream().toList())if(((String)node.getProperty("key")).startsWith(namespace+"/"))node.delete();
        }
        Node ns=namespace(namespace);deleted.add(namespace);
        try(var edges=ns.getRelationships()) {for(Relationship edge:edges)edge.delete();}ns.delete();
        return List.copyOf(deleted);
    }
    public void markBootstrap() {tx.createNode(Label.label("DepositInitialization")).setProperty("id","bootstrap");}
}
