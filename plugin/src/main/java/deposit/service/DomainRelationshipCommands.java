package deposit.service;

import deposit.common.*;
import deposit.repository.CoreRepository;
import java.util.*;
import org.neo4j.graphdb.*;

/** Relationship and membership mutations performed inside the owning domain action. */
final class DomainRelationshipCommands extends BaseService {
    DomainRelationshipCommands(RequestContext c) { super(c); }

    private Node clazz(Map<String,Object> request) {
        if(request.containsKey("classId")==request.containsKey("className"))c.fail("VALIDATION_FAILED","Supply classId or className");
        if(request.containsKey("classId"))return required("Class",request.get("classId"));
        Node n=repo.find("Class",Map.of("namespaceId",c.namespaceId,"name",name(request.get("className"))));
        if(n==null)c.fail("NOT_FOUND","Class not found");
        return n;
    }
    private Node newDeclaration(Node clazz,String name,String type,Object order,Object comment) {
        if(repo.declaration(clazz,name)!=null)c.fail("DUPLICATE","Declaration exists");
        return repo.attach("ClassDescriptor",clazz,c.actorId,Json.map("name",name,"type",type,
            "order",integer(order),"comment",string(comment)));
    }
    private void ensureClassRelationship(Relationship edge) {
        if(!CoreRepository.kind(edge).equals("Relationship"))throw new IllegalStateException("Invalid Object relationship");
        Node source=repo.objectClass(edge.getStartNode()),target=repo.objectClass(edge.getEndNode());
        String label=(String)edge.getProperty("label");
        if(repo.relationship(source,target,label)==null)repo.createRelationship(source,target,label,Map.of("comment",""),c.actorId);
    }
    ActionService.Change membership(Map<String,Object> request) {
        fields(request,"objectId","classId","className");
        Node object=required("Object",request.get("objectId")),destination=clazz(request),source=repo.objectClass(object);
        boolean changed=!source.equals(destination);
        if(changed) {
            for(Node descriptor:repo.children(object,"Descriptor")) {
                String name=(String)descriptor.getProperty("name");
                Node sourceDecl=repo.declaration(source,name),destDecl=repo.declaration(destination,name);
                if(destDecl==null)newDeclaration(destination,name,(String)sourceDecl.getProperty("type"),sourceDecl.getProperty("order"),sourceDecl.getProperty("comment"));
                else if(!sourceDecl.getProperty("type").equals(destDecl.getProperty("type")))c.fail("TYPE_CONFLICT","Destination declaration differs");
            }
        }
        Relationship rel=repo.assign(object,destination,c.actorId);
        if(changed) {
            repo.audit(object,c.actorId);
            try(var edges=object.getRelationships(RelationshipType.withName("RELATED_TO"))) {
                for(var edge:edges) {c.state.kernel.assertOpen();ensureClassRelationship(edge);}
            }
        }
        return new ActionService.Change(new deposit.common.Results.Outcome(null,rel,repo.data(rel)),changed);
    }
    ActionService.Change relationship(String verb,String kind,Map<String,Object> request) {
        boolean object=kind.equals("Relationship");String field=object?"weight":"comment";
        if(verb.equals("create")) {
            fields(request,"sourceId","targetId","label",field);
            Node source=required(object?"Object":"Class",request.get("sourceId")),target=required(object?"Object":"Class",request.get("targetId"));
            String label=name(request.get("label"));
            if(repo.relationship(source,target,label)!=null)c.fail("DUPLICATE","Relationship exists");
            Object value=object?ValueCodec.normalize("Float",request.get("weight")):string(request.getOrDefault("comment",""));
            Relationship rel=repo.createRelationship(source,target,label,Json.map(field,value),c.actorId);
            if(object)ensureClassRelationship(rel);
            return new ActionService.Change(new deposit.common.Results.Outcome(null,rel,repo.data(rel)),true);
        }
        if(verb.equals("set"))fields(request,"id",field);else fields(request,"id");
        Relationship rel=repo.readRelationship(c.namespaceId,kind,RequestContext.uuid(request.get("id")));
        if(rel==null)c.fail("NOT_FOUND","Relationship not found");
        if(verb.equals("set")) {
            var updates=new LinkedHashMap<String,Object>();
            if(request.containsKey(field))updates.put(field,object?ValueCodec.normalize("Float",request.get(field)):string(request.get(field)));
            boolean changed=repo.update(rel,updates,c.actorId);
            return new ActionService.Change(new deposit.common.Results.Outcome(null,rel,repo.data(rel)),changed);
        }
        if(!object&&repo.hasRelationshipInstance(rel,c.state.kernel::assertOpen))
            c.fail("VALIDATION_FAILED","Class Relationship is used");
        var deleted=new ArrayList<String>();repo.remove(rel,deleted);
        return new ActionService.Change(new deposit.common.Results.Outcome(null,null,Json.map("deletedIds",deleted)),true);
    }
}
