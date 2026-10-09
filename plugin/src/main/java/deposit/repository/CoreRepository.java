package deposit.repository;

import deposit.common.*;
import deposit.common.Results.Outcome;
import java.time.*;
import java.util.*;
import org.neo4j.graphdb.*;

/** Graph storage only. Authorization and action boundaries belong to services. */
public final class CoreRepository {
    private final Transaction tx;
    private final ZonedDateTime timestamp;
    public CoreRepository(Transaction tx,ZonedDateTime timestamp) {this.tx=tx;this.timestamp=timestamp;}
    public Node find(String label,String property,Object value) {return tx.findNode(Label.label(label),property,value);}
    public Node find(String label,Map<String,Object> properties) {
        try(var nodes=tx.findNodes(Label.label(label),properties)) {return nodes.hasNext()?nodes.next():null;}
    }
    public Node entity(String label,String namespace,String id) {return find(label,Map.of("namespaceId",namespace,"id",id));}
    public List<Node> nodes(String label,Map<String,Object> properties) {
        try(var nodes=properties.isEmpty()?tx.findNodes(Label.label(label)):tx.findNodes(Label.label(label),properties)) {return nodes.stream().sorted(Comparator.comparing(n->(String)n.getProperty("id",""))).toList();}
    }
    public static String id(Entity n) {return (String)n.getProperty("id");}
    public static String kind(Node node) {
        for(String label:List.of("Namespace","User","Class","Object","ClassDescriptor","Descriptor","UserTool")) if(node.hasLabel(Label.label(label))) return label;
        throw new IllegalStateException("Unknown domain label");
    }
    public Node namespace(String id) {return find("Namespace","id",id);}
    public long sequence(String id) {Node ns=namespace(id);if(ns==null) throw ExceptionHandler.fail("NOT_FOUND","Namespace not found");return ((Number)ns.getProperty("sequence")).longValue();}
    public void lockNamespace(String id) {tx.acquireWriteLock(namespace(id));}
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
        Node n=namespace==null?tx.createNode(Label.label(label)):tx.createNode(Label.label(label),Label.label("DepositLive"));
        n.setProperty("id",UUID.randomUUID().toString());
        if(namespace!=null) n.setProperty("namespaceId",namespace);
        var now=timestamp;n.setProperty("createdAt",now);n.setProperty("createdBy",actor==null?id(n):actor);
        n.setProperty("updatedAt",now);n.setProperty("updatedBy",actor==null?id(n):actor);
        properties.forEach((k,v)->{if(v!=null)n.setProperty(k,property(v));});
        if(label.equals("User"))tx.createNode(Label.label("DepositActorLock")).setProperty("id",id(n));
        if(namespace!=null && Set.of("Class","Object","UserTool").contains(label)) namespace(namespace).createRelationshipTo(n,RelationshipType.withName("CONTAINS"));
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
        Relationship existing=membership(object);
        if(existing!=null && existing.getEndNode().equals(clazz)) return existing;
        String id=existing==null?UUID.randomUUID().toString():id(existing);
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
        var fields=new LinkedHashMap<>(props);fields.put("ownerId",id(owner));
        Node n=create(label,(String)owner.getProperty("namespaceId"),actor,fields);owner.createRelationshipTo(n,RelationshipType.withName("HAS_DESCRIPTOR"));return n;
    }
    public List<Node> members(Node clazz) {
        var result=new ArrayList<Node>();try(var edges=clazz.getRelationships(Direction.INCOMING,RelationshipType.withName("MEMBER_OF"))) {for(var edge:edges) result.add(edge.getStartNode());}
        result.sort(Comparator.comparing(CoreRepository::id));return result;
    }
    public Node owner(Node descriptor) {return descriptor.getSingleRelationship(RelationshipType.withName("HAS_DESCRIPTOR"),Direction.INCOMING).getStartNode();}
    public String descriptorType(Node descriptor) {return (String)declaration(objectClass(owner(descriptor)),(String)descriptor.getProperty("name")).getProperty("type");}
    public Map<String,Object> data(Node node) {
        String kind=kind(node);var result=new LinkedHashMap<String,Object>();
        if(!kind.equals("User")) result.put("kind",kind);
        for(String key:List.of("id","namespaceId","createdAt","createdBy","updatedAt","updatedBy","name","username","enabled","serverRole","description","depositVersion","historyMode","historyEpochId","sequence","order","comment","type")) {
            if(node.hasProperty(key)) {Object v=node.getProperty(key);result.put(key,v instanceof java.time.temporal.TemporalAccessor?v.toString():v);}
        }
        if(!kind.equals("User") && !result.containsKey("namespaceId"))result.put("namespaceId",kind.equals("Namespace")?id(node):null);
        if(node.hasProperty("position"))result.put("position",Arrays.stream((double[])node.getProperty("position")).boxed().toList());
        if(kind.equals("Object")) {result.put("classId",id(objectClass(node)));var descriptors=new LinkedHashMap<String,Object>();for(Node d:children(node,"Descriptor")) descriptors.put((String)d.getProperty("name"),ValueCodec.decode(descriptorType(d),d.getProperty("value",null)));result.put("descriptors",descriptors);}
        if(kind.equals("Class"))result.put("descriptors",children(node,"ClassDescriptor").stream().map(this::data).toList());
        if(kind.equals("Descriptor")) {result.put("objectId",node.getProperty("ownerId"));result.put("value",ValueCodec.decode(descriptorType(node),node.getProperty("value",null)));}
        if(kind.equals("ClassDescriptor"))result.put("classId",node.getProperty("ownerId"));
        if(kind.equals("UserTool"))result.put("data",node.getProperty("data"));
        return result;
    }
    public static String kind(Relationship rel) {
        String source=kind(rel.getStartNode()),target=kind(rel.getEndNode());
        if(rel.isType(RelationshipType.withName("MEMBER_OF"))&&source.equals("Object")&&target.equals("Class"))return "Membership";
        if(rel.isType(RelationshipType.withName("RELATED_TO"))&&source.equals(target)) {
            if(source.equals("Object"))return "Relationship";
            if(source.equals("Class"))return "ClassRelationship";
        }
        throw new IllegalStateException("Invalid domain edge endpoints");
    }
    public Map<String,Object> data(Relationship rel) {
        String kind=kind(rel);var result=new LinkedHashMap<String,Object>();result.put("kind",kind);
        rel.getAllProperties().forEach((k,v)->result.put(k,v instanceof java.time.temporal.TemporalAccessor?v.toString():v));
        if(kind.equals("Membership")) {result.put("objectId",id(rel.getStartNode()));result.put("classId",id(rel.getEndNode()));}
        else {result.put("sourceId",id(rel.getStartNode()));result.put("targetId",id(rel.getEndNode()));if(kind.equals("Relationship"))result.putIfAbsent("weight",null);}
        return result;
    }
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
        Relationship rel=source.createRelationshipTo(target,RelationshipType.withName("RELATED_TO"));
        rel.setProperty("id",UUID.randomUUID().toString());rel.setProperty("namespaceId",source.getProperty("namespaceId"));rel.setProperty("label",label);
        rel.setProperty("createdAt",timestamp);rel.setProperty("createdBy",actor);audit(rel,actor);
        properties.forEach((key,value)->{if(value!=null)rel.setProperty(key,value);});
        Node registry=tx.createNode(Label.label("DepositEdgeIdentity"));registry.setProperty("key",edgeKey(rel));registry.setProperty("id",id(rel));return rel;
    }
    public void remove(Relationship rel,List<String> deleted) {
        Node registry=find("DepositEdgeIdentity","key",edgeKey(rel));
        if(registry==null||!id(rel).equals(registry.getProperty("id")))throw new IllegalStateException("Missing edge identity");
        registry.delete();deleted.add(id(rel));rel.delete();
    }
    public void remove(Node node,List<String> deleted) {
        deleted.add(id(node));
        if(kind(node).equals("Object")) {
            for(Node d:children(node,"Descriptor"))remove(d,deleted);
        }
        if(kind(node).equals("Class")) for(Node d:children(node,"ClassDescriptor"))remove(d,deleted);
        try(var edges=node.getRelationships()) {for(var rel:edges) {if(rel.hasProperty("id"))remove(rel,deleted);else rel.delete();}}
        node.delete();
    }
    public Node receipt(String scope,String operation) {return find("DepositReceipt",Map.of("scope",scope,"operationId",operation));}
    public Node binding(String actor,String operation) {return find("DepositOperationBinding",Map.of("actorId",actor,"operationId",operation));}
    public void saveReceipt(RequestContext c,String scope,String hash,Outcome outcome) {
        Node receipt=tx.createNode(Label.label("DepositReceipt"));
        receipt.setProperty("scope",scope);receipt.setProperty("operationId",c.operationId);receipt.setProperty("actorId",c.actorId);receipt.setProperty("requestHash",hash);
        receipt.setProperty("outcome",Json.write(outcome.data()));receipt.setProperty("revision",c.revision);
        receipt.setProperty("administrativeMode",c.state.scoped?c.state.administrative:(c.session!=null&&c.session.administrative));
        if(outcome.node()!=null) {receipt.setProperty("nodeKind",kind(outcome.node()));receipt.setProperty("entityId",id(outcome.node()));}
        if(outcome.rel()!=null) {receipt.setProperty("relId",id(outcome.rel()));receipt.setProperty("relKind",kind(outcome.rel()));receipt.setProperty("objectId",id(outcome.rel().getStartNode()));}
        Node binding=tx.createNode(Label.label("DepositOperationBinding"));binding.setProperty("actorId",c.actorId);binding.setProperty("operationId",c.operationId);binding.setProperty("scope",scope);
    }
    public Outcome replay(Node receipt,String scope) {
        Node node=null;Relationship rel=null;
        if(receipt.hasProperty("entityId")) {String label=(String)receipt.getProperty("nodeKind");String id=(String)receipt.getProperty("entityId");node=Set.of("Namespace","User").contains(label)?find(label,"id",id):entity(label,scope,id);}
        if(receipt.hasProperty("relId")) {
            String category=(String)receipt.getProperty("relKind","Membership");
            if(category.equals("Membership")) {Node object=entity("Object",scope,(String)receipt.getProperty("objectId"));if(object!=null) {var candidate=membership(object);if(candidate!=null && receipt.getProperty("relId").equals(id(candidate)))rel=candidate;}}
            else rel=relationship(scope,category,(String)receipt.getProperty("relId"));
        }
        return new Outcome(node,rel,Json.read((String)receipt.getProperty("outcome")));
    }
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
        // Receipts and operation bindings intentionally survive deletion, without native links.
        for(String label:List.of("Object","Descriptor","ClassDescriptor","Class","UserTool")) {
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
