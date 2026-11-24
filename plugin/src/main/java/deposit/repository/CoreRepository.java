package deposit.repository;

import deposit.common.*;
import deposit.common.Results.Outcome;
import java.time.*;
import java.util.*;
import org.neo4j.graphdb.*;

/** Graph storage only. Authorization and action boundaries belong to services. */
public final class CoreRepository implements CoreProjectionRepository.Access, OperationReceiptRepository.Access {
    private final Transaction tx;
    private final RequestContext context;
    private final ZonedDateTime timestamp;
    private final CoreProjectionRepository projections;
    private final OperationReceiptRepository receipts;
    public CoreRepository(Transaction tx,ZonedDateTime timestamp) {this(tx,timestamp,null);}
    public CoreRepository(RequestContext context) {this(context.tx,context.timestamp,context);}
    private CoreRepository(Transaction tx,ZonedDateTime timestamp,RequestContext context) {
        this.tx=tx;this.timestamp=timestamp;this.context=context;
        projections=new CoreProjectionRepository(this);receipts=new OperationReceiptRepository(tx,this);
    }
    private HistoryMutation historyMutation() {return context==null?null:context.historyMutation;}
    private PatternChanges patternChanges() {return context==null?null:context.patternChanges;}
    void observe(String point) {if(context!=null)context.server.storagePoint(point);}
    public Node find(String label,String property,Object value) {return tx.findNode(Label.label(label),property,value);}
    public Node find(String label,Map<String,Object> properties) {
        try(var nodes=tx.findNodes(Label.label(label),properties)) {return nodes.hasNext()?nodes.next():null;}
    }
    public Node entity(String label,String namespace,String id) {return find(label,Map.of("namespaceId",namespace,"id",id));}
    public ResourceIterator<Node> scanNodes(String label,Map<String,Object> properties){return properties.isEmpty()?tx.findNodes(Label.label(label)):tx.findNodes(Label.label(label),properties);}
    public List<Node> nodes(String label,Map<String,Object> properties) {
        try(var nodes=properties.isEmpty()?tx.findNodes(Label.label(label)):tx.findNodes(Label.label(label),properties)) {return nodes.stream().sorted(Comparator.comparing(n->(String)n.getProperty("id",""))).toList();}
    }
    public List<Node> nodePage(String kind,String namespace,String memberClass,long offset,long limit) {
        String order=kind.equals("Class")||kind.equals("ClassDescriptor")?"coalesce(n.order,0), n.id":"n.id";
        // The caller supplies one of the fixed scoped domain labels.
        String match=memberClass==null?"(n:"+kind+" {namespaceId:$namespace})":
            "(n:Object)-[:MEMBER_OF]->(:Class {namespaceId:$namespace,id:$memberClass})";
        var parameters=Json.map("namespace",namespace,"memberClass",memberClass,"offset",offset,"limit",limit);
        try(var result=tx.execute("MATCH "+match+" RETURN n ORDER BY "+order+" SKIP $offset LIMIT $limit",parameters)) {
            var page=new ArrayList<Node>();
            while(result.hasNext())page.add((Node)result.next().get("n"));
            return page;
        }
    }
    public static String id(Entity n) {return (String)n.getProperty("id");}
    public static String kind(Node node) {
        for(String label:List.of("Namespace","User","Class","Object","ClassDescriptor","Descriptor","Pattern","UserTool","DGeometry","DResource")) if(node.hasLabel(Label.label(label))) return label;
        throw new IllegalStateException("Unknown domain label");
    }
    public Node namespace(String id) {
        if(context==null||!context.protectedAuthorization)return find("Namespace","id",id);
        return context.state.protectedNamespaces.computeIfAbsent(id,key->find("Namespace","id",key));
    }
    public long sequence(String id) {Node ns=namespace(id);if(ns==null) throw ExceptionHandler.fail("NOT_FOUND","Namespace not found");return namespaceSequence(ns);}
    public long namespaceSequence(Node namespace) {
        observe("namespace-sequence-read");
        return sequence(namespace);
    }
    public static long sequence(Node namespace) {return ((Number)namespace.getProperty("sequence")).longValue();}
    public void lockNamespace(String id) {Node lock=find("DepositNamespaceLock","id",id);if(lock==null)throw new IllegalStateException("Namespace coordination missing");tx.acquireWriteLock(lock);}
    public void lockActor(String id) {tx.acquireWriteLock(find("DepositActorLock","id",id));}
    public String role(String actor,String namespace) {
        return role(find("User","id",actor),namespace);
    }
    public String role(Node user,String namespace) {
        if(user==null)return null;
        try(var edges=user.getRelationships(Direction.OUTGOING,RelationshipType.withName("HAS_NAMESPACE_ROLE"))) {
            for(Relationship edge:edges) if(namespace.equals(edge.getEndNode().getProperty("id",null))) return (String)edge.getProperty("role");
        }return null;
    }
    public boolean setRole(String actor,String namespace,String role) {
        return setRole(find("User","id",actor),namespace(namespace),role);
    }
    public boolean setRole(Node user,Node ns,String role) {
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
        return roleEdges(namespace,()->{}).stream().map(CoreRepository::roleData).toList();
    }
    public List<Relationship> roleEdges(String namespace,Runnable check) {
        var result=new ArrayList<Relationship>();
        try(var edges=namespace(namespace).getRelationships(Direction.INCOMING,RelationshipType.withName("HAS_NAMESPACE_ROLE"))) {
            for(Relationship edge:edges) {check.run();result.add(edge);}
        }
        result.sort(Comparator.comparing(edge->id(edge.getStartNode())));
        return result;
    }
    public static Map<String,Object> roleData(Relationship edge) {
        return Json.map("userId",id(edge.getStartNode()),"role",edge.getProperty("role"));
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
        var history=historyMutation();if(history!=null)history.created(n);
        var patterns=patternChanges();
        if(patterns!=null)switch(label) {
            case "Class" -> patterns.classChanged(n,null,(String)n.getProperty("name"));
            case "ClassDescriptor" -> patterns.declarationCreated(n);
            case "Pattern" -> patterns.patternCreated(n);
            default -> {}
        }
        return n;
    }
    private static Object property(Object v) {if(v instanceof List<?> list) return list.stream().mapToDouble(x->((Number)x).doubleValue()).toArray();return v;}
    public boolean update(Entity entity,Map<String,Object> values,String actor) {
        boolean changed=false;
        var history=historyMutation();
        var patterns=patternChanges();
        for(var entry:values.entrySet()) {
            String field=entry.getKey();
            Object value=entry.getValue()==null?null:property(entry.getValue());Object old=entity.getProperty(field,null);
            if(Objects.deepEquals(old,value))continue;
            if(!changed&&history!=null)history.changing(entity);
            changed=true;
            Node pattern=null;
            if(patterns!=null&&entity instanceof Node node) {
                if(field.equals("name")&&node.hasLabel(Label.label("Class")))patterns.classChanged(node,(String)old,(String)value);
                if(field.equals("type")&&node.hasLabel(Label.label("ClassDescriptor")))patterns.declarationChanged(node);
                if(field.equals("definition")&&node.hasLabel(Label.label("Pattern"))) {
                    pattern=node;
                    patterns.definitionChanging(pattern);
                }
            }
            if(value==null)entity.removeProperty(field);else entity.setProperty(field,value);
            if(pattern!=null)patterns.definitionChanged(pattern);
        }
        if(changed)auditProperties(entity,actor);
        return changed;
    }
    public void audit(Entity entity,String actor) {
        var history=historyMutation();if(history!=null)history.changing(entity);
        auditProperties(entity,actor);
    }
    private void auditProperties(Entity entity,String actor) {entity.setProperty("updatedAt",timestamp);entity.setProperty("updatedBy",actor);}
    public long advanceNamespace(Node namespace,long sequence,String actor) {
        long next=sequence+1;
        namespace.setProperty("sequence",next);
        audit(namespace,actor);
        return next;
    }
    public Relationship membership(Node object) {return object.getSingleRelationship(RelationshipType.withName("MEMBER_OF"),Direction.OUTGOING);}
    public Node objectClass(Node object) {var membership=membership(object);if(membership==null) throw new IllegalStateException("Object has no Class");return membership.getEndNode();}
    public Relationship assign(Node object,Node clazz,String actor) {
        return assignRestored(object,clazz,actor,null);
    }
    public Relationship assignRestored(Node object,Node clazz,String actor,String restoredId) {
        Relationship existing=membership(object);
        if(existing!=null && existing.getEndNode().equals(clazz)) return existing;
        var history=historyMutation();if(history!=null)history.changing(object);
        String id=restoredId!=null?restoredId:existing==null?UUID.randomUUID().toString():id(existing);
        var properties=existing==null?new HashMap<String,Object>():new HashMap<>(existing.getAllProperties());
        if(existing!=null)existing.delete();
        String key=object.getProperty("namespaceId")+"/Membership/"+id(object);
        if(find("DepositEdgeIdentity","key",key)==null) {Node registry=tx.createNode(Label.label("DepositEdgeIdentity"));registry.setProperty("key",key);registry.setProperty("id",id);}
        Relationship rel=object.createRelationshipTo(clazz,RelationshipType.withName("MEMBER_OF"));properties.forEach((name,value)->{if(!name.equals("id"))rel.setProperty(name,value);});
        rel.setProperty("id",id);rel.setProperty("namespaceId",object.getProperty("namespaceId"));
        if(existing==null) {rel.setProperty("createdAt",timestamp);rel.setProperty("createdBy",actor);}auditProperties(rel,actor);return rel;
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
        return descriptorValue(descriptor,descriptorType(descriptor));
    }
    public Object descriptorValue(Node descriptor,String type) {
        if(type.equals("DGeometry")){var g=geometry();Node node=g.geometry(descriptor);return node==null?null:g.value(node);}
        if(type.equals("DResource")){Node node=resources().linked(descriptor);return node==null?null:Json.map("resourceId",id(node));}
        return ValueCodec.decode(type,descriptor.getProperty("value",null));
    }
    public boolean writeValue(Node descriptor,String type,Object value,String actor) {
        var history=historyMutation();if(history!=null)history.changing(descriptor);
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
        var state=server.state(internal.kernelTransaction());
        boolean reuse=context!=null&&context.protectedAuthorization&&actor.equals(context.actorId)
            &&namespace.equals(state.namespace)&&state.protectedActor!=null;
        Node user=reuse?state.protectedActor:find("User","id",actor);
        if(user!=null&&state.administrative&&"administrator".equals(user.getProperty("serverRole",null)))return "owner";
        if(reuse&&state.namespaceRoles.containsKey(namespace))return state.namespaceRoles.get(namespace);
        return role(user,namespace);
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
    public TreeMap<String,Map<String,String>> namespaceSchema(String namespace) {return projections.namespaceSchema(namespace);}
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
        return relationships(namespace,category,()->{});
    }
    public List<Relationship> relationships(String namespace,String category,Runnable check) {
        observe("relationship-collection");
        var results=new ArrayList<Relationship>();String label=category.equals("ClassRelationship")?"Class":"Object";
        for(Node source:nodes(label,Map.of("namespaceId",namespace))) {
            check.run();
            collectRelationships(source,Direction.OUTGOING,namespace,category,check,results);
        }
        results.sort(Comparator.comparing(CoreRepository::id));return results;
    }
    public List<Relationship> relationshipsAt(String namespace,String category,String endpoint,Direction direction,Runnable check) {
        Node node=entity(category.equals("ClassRelationship")?"Class":"Object",namespace,endpoint);
        if(node==null)return List.of();
        var results=new ArrayList<Relationship>();
        collectRelationships(node,direction,namespace,category,check,results);
        results.sort(Comparator.comparing(CoreRepository::id));
        return results;
    }
    private static void collectRelationships(Node node,Direction direction,String namespace,String category,
            Runnable check,List<Relationship> results) {
        try(var edges=node.getRelationships(direction,RelationshipType.withName("RELATED_TO"))) {
            for(var edge:edges) {
                check.run();
                Node other=edge.getOtherNode(node);
                if(!category.equals(kind(edge))||!namespace.equals(other.getProperty("namespaceId",null)))
                    throw new IllegalStateException("Invalid scoped edge");
                results.add(edge);
            }
        }
    }
    public Relationship readRelationship(String namespace,String category,String id) {
        observe("relationship-composite-lookup");
        try(var edges=tx.findRelationships(RelationshipType.withName("RELATED_TO"),Map.of("namespaceId",namespace,"id",id))) {
            while(edges.hasNext()) {
                Relationship edge=edges.next();
                if(category.equals(kind(edge))&&namespace.equals(edge.getStartNode().getProperty("namespaceId",null))
                        &&namespace.equals(edge.getEndNode().getProperty("namespaceId",null)))return edge;
            }
            return null;
        }
    }
    public boolean hasRelationshipInstance(Relationship classRelationship,Runnable check) {
        Object scope=classRelationship.getProperty("namespaceId");
        Object label=classRelationship.getProperty("label");
        Node sourceClass=classRelationship.getStartNode(),targetClass=classRelationship.getEndNode();
        var object=Label.label("Object");
        try(var members=sourceClass.getRelationships(Direction.INCOMING,RelationshipType.withName("MEMBER_OF"))) {
            for(var member:members) {
                check.run();
                Node source=member.getStartNode();
                if(!source.hasLabel(object)||!scope.equals(source.getProperty("namespaceId",null)))continue;
                try(var edges=source.getRelationships(Direction.OUTGOING,RelationshipType.withName("RELATED_TO"))) {
                    for(var edge:edges) {
                        check.run();
                        if(!label.equals(edge.getProperty("label",null))||!scope.equals(edge.getProperty("namespaceId",null)))continue;
                        Node target=edge.getEndNode();
                        if(!target.hasLabel(object)||!scope.equals(target.getProperty("namespaceId",null)))continue;
                        var membership=membership(target);
                        if(membership!=null&&targetClass.equals(membership.getEndNode()))return true;
                    }
                }
            }
        }
        return false;
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
        rel.setProperty("createdAt",timestamp);rel.setProperty("createdBy",actor);auditProperties(rel,actor);
        properties.forEach((key,value)->{if(value!=null)rel.setProperty(key,value);});
        Node registry=tx.createNode(Label.label("DepositEdgeIdentity"));registry.setProperty("key",edgeKey(rel));registry.setProperty("id",id(rel));
        var history=historyMutation();if(history!=null)history.created(rel);
        return rel;
    }
    public void remove(Relationship rel,List<String> deleted) {
        var history=historyMutation();if(history!=null)history.deleting(rel);
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
        var history=historyMutation();if(history!=null)history.deleting(node);
        String kind=kind(node);
        var patterns=patternChanges();
        if(patterns!=null)switch(kind) {
            case "Class" -> patterns.classChanged(node,(String)node.getProperty("name"),null);
            case "ClassDescriptor" -> patterns.declarationRemoved(node);
            case "Pattern" -> patterns.patternRemoved(node);
            default -> {}
        }
        deleted.add(id(node));
        if(kind.equals("Descriptor"))removeValue(node,deleted);
        if(kind.equals("Object")) {
            for(Node d:children(node,"Descriptor"))remove(d,deleted);
        }
        if(kind.equals("Class")) for(Node d:children(node,"ClassDescriptor"))remove(d,deleted);
        try(var edges=node.getRelationships()) {for(var rel:edges) {if(rel.hasProperty("id"))remove(rel,deleted);else rel.delete();}}
        node.delete();
    }
    public Node receipt(String scope,String operation) {return receipts.receipt(scope,operation);}
    public Node receipt(String actor,String scope,String operation) {return receipts.receipt(actor,scope,operation);}
    public Node receipt(RequestContext context,String scope) {return receipt(context.actorId,scope,context.operationId);}
    public void saveReceipt(RequestContext c,String scope,String hash,Outcome outcome) {receipts.saveReceipt(c,scope,hash,outcome);}
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
        // Remove owned values while ownership/declarations still exist; operational records survive.
        for(Node descriptor:nodes("Descriptor",Map.of("namespaceId",namespace))) {var ids=new ArrayList<String>();removeValue(descriptor,ids);deleted.addAll(ids);}
        geometry().deleteLayers(namespace);
        for(Node grant:nodes("DepositExportGrant",Map.of("namespaceId",namespace)))grant.setProperty("expires",0L);
        for(Node loc:nodes("DepositResourceLocation",Map.of("namespaceId",namespace))) {loc.setProperty("leaseUntil",0L);loc.setProperty("everAttached",true);resources().orphan(loc);}
        // Epoch heads plus complete before/after snapshot chains are retained until
        // deletion commits; this also keeps the clone-only history identities intact.
        new HistoryRecordDeletion(tx).deleteNamespace(namespace);
        for(Node resource:nodes("DResource",Map.of("namespaceId",namespace))) {
            try(var edges=resource.getRelationships()) {for(Relationship edge:edges)edge.delete();}
            deleted.add(id(resource));resource.delete();
        }
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
