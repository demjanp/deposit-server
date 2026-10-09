package deposit.service;

import deposit.common.*;
import deposit.common.Results.Outcome;
import deposit.repository.CoreRepository;
import java.util.*;
import org.neo4j.graphdb.*;

public final class DomainService extends ActionService {
    public DomainService(RequestContext c) {super(c);}
    private Node required(String label,Object id) {
        Node n=repo.entity(label,c.namespaceId,RequestContext.uuid(id));if(n==null)c.fail("NOT_FOUND",label+" not found");return n;
    }
    private Node clazz(Map<String,Object> request,boolean infer) {
        if(request.containsKey("classId")==request.containsKey("className"))c.fail("VALIDATION_FAILED","Supply classId or className");
        if(request.containsKey("classId"))return required("Class",request.get("classId"));
        String name=name(request.get("className"));Node n=repo.find("Class",Map.of("namespaceId",c.namespaceId,"name",name));
        if(n==null && infer)n=newClass(Json.map("name",name));
        if(n==null)c.fail("NOT_FOUND","Class not found");return n;
    }
    private Node newClass(Map<String,Object> request) {
        String name=name(request.get("name"));
        if(repo.find("Class",Map.of("namespaceId",c.namespaceId,"name",name))!=null)c.fail("DUPLICATE","Class name exists");
        Node n=repo.create("Class",c.namespaceId,c.actorId,Json.map("name",name,"position",position(request.getOrDefault("position",List.of(0.0,0.0))),"order",integer(request.getOrDefault("order",0L)),"comment",string(request.getOrDefault("comment",""))));
        Object descriptors=request.getOrDefault("descriptors",List.of());if(!(descriptors instanceof List<?>))c.fail("VALIDATION_FAILED","Class descriptors must be a list");
        for(Object declaration:(List<?>)descriptors) {var d=ValueCodec.map(declaration);fields(d,"name","type","order","comment");newDeclaration(n,d);}
        return n;
    }
    private Node newDeclaration(Node clazz,Map<String,Object> request) {
        String name=name(request.get("name")),type=ValueCodec.type(request.get("type"));
        if(repo.declaration(clazz,name)!=null)c.fail("DUPLICATE","Declaration exists");
        return repo.attach("ClassDescriptor",clazz,c.actorId,Json.map("name",name,"type",type,"order",integer(request.getOrDefault("order",0L)),"comment",string(request.getOrDefault("comment",""))));
    }
    private record DescriptorWrite(Node descriptor,boolean changed) {}
    private DescriptorWrite descriptor(Node object,String name,Object input,boolean createOnly) {
        Node old=repo.descriptor(object,name);if(old!=null&&createOnly)c.fail("DUPLICATE","Descriptor exists");
        Node declaration=repo.declaration(repo.objectClass(object),name);
        Object payload=input;
        if(declaration==null) {
            var typed=ValueCodec.map(input);fields(typed,"type","value");
            if(!typed.containsKey("value"))c.fail("VALIDATION_FAILED","Undeclared value requires explicit type and value");
            String type=ValueCodec.type(typed.get("type"));payload=typed.get("value");
            ValueCodec.normalize(type,payload);
            declaration=newDeclaration(repo.objectClass(object),Json.map("name",name,"type",type));
        }
        String type=(String)declaration.getProperty("type");Object normalized=ValueCodec.normalize(type,payload);
        Object stored=ValueCodec.storage(type,normalized);String encoding=Set.of("List","DDict").contains(type)?"json":"native";
        if(old==null)return new DescriptorWrite(repo.attach("Descriptor",object,c.actorId,Json.map("name",name,"encoding",encoding,"value",stored)),true);
        return new DescriptorWrite(old,repo.update(old,Json.map("encoding",encoding,"value",stored),c.actorId));
    }
    private boolean descriptors(Node object,Map<String,Object> request) {
        Map<String,Object> values=ValueCodec.map(request.getOrDefault("descriptors",Map.of()));
        Object clear=request.getOrDefault("clearDescriptors",List.of());if(!(clear instanceof List<?>))c.fail("VALIDATION_FAILED","clearDescriptors must be a list");
        var normalized=new LinkedHashMap<String,Object>();
        for(var entry:values.entrySet()) {String name=name(entry.getKey());if(normalized.containsKey(name))c.fail("DUPLICATE","Duplicate descriptor name");normalized.put(name,entry.getValue());}
        Set<String> cleared=new HashSet<>();for(Object value:(List<?>)clear) {String name=name(value);if(!cleared.add(name)||normalized.containsKey(name))c.fail("VALIDATION_FAILED","Conflicting descriptor patch");}
        boolean changed=false;
        for(var entry:normalized.entrySet())changed=descriptor(object,entry.getKey(),entry.getValue(),false).changed()||changed;
        for(String name:cleared) {Node d=repo.descriptor(object,name);if(d!=null) {repo.remove(d,new ArrayList<>());changed=true;}}
        return changed;
    }
    public Outcome create(String kind,Map<String,Object> request) {
        switch(kind) {
            case "Class" -> fields(request,"name","descriptors","position","order","comment");
            case "Object" -> fields(request,"classId","className","descriptors","position");
            case "ClassDescriptor" -> fields(request,"classId","name","type","order","comment");
            case "Descriptor" -> fields(request,"objectId","name","value");
            default -> throw new IllegalArgumentException();
        }
        return action("deposit.create."+lower(kind),request,false,"editor",()->{
            Node n;
            switch(kind) {
                case "Class" -> n=newClass(request);
                case "Object" -> {
                    Node clazz=clazz(request,true);n=repo.create("Object",c.namespaceId,c.actorId,Map.of("position",position(request.getOrDefault("position",List.of(0.0,0.0)))));
                    repo.assign(n,clazz,c.actorId);descriptors(n,request);
                }
                case "ClassDescriptor" -> n=newDeclaration(required("Class",request.get("classId")),request);
                case "Descriptor" -> {if(!request.containsKey("value"))c.fail("VALIDATION_FAILED","value required");n=descriptor(required("Object",request.get("objectId")),name(request.get("name")),request.get("value"),true).descriptor();}
                default -> throw new IllegalArgumentException();
            }
            return new Change(outcome(n),true);
        });
    }
    public Outcome set(String kind,Map<String,Object> request) {
        switch(kind) {
            case "Class" -> fields(request,"id","name","position","order","comment");
            case "Object" -> fields(request,"id","position","descriptors","clearDescriptors");
            case "ClassDescriptor" -> fields(request,"id","order","comment");
            case "Descriptor" -> fields(request,"objectId","name","value");
            default -> throw new IllegalArgumentException();
        }
        return action("deposit.set."+lower(kind),request,false,"editor",()->{
            if(kind.equals("Descriptor")) {if(!request.containsKey("value"))c.fail("VALIDATION_FAILED","value required");var result=descriptor(required("Object",request.get("objectId")),name(request.get("name")),request.get("value"),false);return new Change(outcome(result.descriptor()),result.changed());}
            Node n=required(kind,request.get("id"));var updates=new LinkedHashMap<String,Object>();
            if(request.containsKey("name")) {
                String name=name(request.get("name"));Node found=repo.find("Class",Map.of("namespaceId",c.namespaceId,"name",name));if(found!=null&&!found.equals(n))c.fail("DUPLICATE","Class name exists");updates.put("name",name);
            }
            if(request.containsKey("position"))updates.put("position",position(request.get("position")));
            if(request.containsKey("order"))updates.put("order",integer(request.get("order")));
            if(request.containsKey("comment"))updates.put("comment",string(request.get("comment")));
            boolean changed=repo.update(n,updates,c.actorId);
            if(kind.equals("Object")&&descriptors(n,request)) {repo.audit(n,c.actorId);changed=true;}
            return new Change(outcome(n),changed);
        });
    }
    public Outcome remove(String kind,Map<String,Object> request) {
        fields(request,"id");
        return action("deposit.delete."+lower(kind),request,false,"editor",()->{
            Node n=required(kind,request.get("id"));
            if(kind.equals("Class")&&!repo.members(n).isEmpty())c.fail("VALIDATION_FAILED","Class has members");
            if(kind.equals("ClassDescriptor"))for(Node object:repo.members(repo.owner(n)))if(repo.descriptor(object,(String)n.getProperty("name"))!=null)c.fail("VALIDATION_FAILED","Declaration is populated");
            var deleted=new ArrayList<String>();repo.remove(n,deleted);return new Change(new Outcome(null,null,Json.map("deletedIds",deleted)),true);
        });
    }
    public Outcome membership(Map<String,Object> request) {
        fields(request,"objectId","classId","className");
        return action("deposit.set.membership",request,false,"editor",()->{
            Node object=required("Object",request.get("objectId")),destination=clazz(request,false),source=repo.objectClass(object);
            boolean changed=!source.equals(destination);
            if(changed) {
                for(Node descriptor:repo.children(object,"Descriptor")) {
                    String name=(String)descriptor.getProperty("name");Node sourceDecl=repo.declaration(source,name),destDecl=repo.declaration(destination,name);
                    if(destDecl==null)newDeclaration(destination,Json.map("name",name,"type",sourceDecl.getProperty("type"),"order",sourceDecl.getProperty("order"),"comment",sourceDecl.getProperty("comment")));
                    else if(!sourceDecl.getProperty("type").equals(destDecl.getProperty("type")))c.fail("TYPE_CONFLICT","Destination declaration differs");
                }
            }
            Relationship rel=repo.assign(object,destination,c.actorId);if(changed)repo.audit(object,c.actorId);
            return new Change(new Outcome(null,rel,repo.data(rel)),changed);
        });
    }
    public Outcome convert(Map<String,Object> request) {
        fields(request,"id","type","conversion");
        return action("deposit.set.classDescriptorType",request,false,"editor",()->{
            Node declaration=required("ClassDescriptor",request.get("id"));String old=(String)declaration.getProperty("type"),type=ValueCodec.type(request.get("type")),conversion=string(request.get("conversion"));
            if(!Set.of("identity","integerToFloat","floatToIntegerExact").contains(conversion))c.fail("VALIDATION_FAILED","Invalid conversion");
            if(conversion.equals("integerToFloat")&&!(old.equals("Integer")&&type.equals("Float")))c.fail("TYPE_CONFLICT","Conversion requires Integer to Float");
            if(conversion.equals("floatToIntegerExact")&&!(old.equals("Float")&&type.equals("Integer")))c.fail("TYPE_CONFLICT","Conversion requires Float to Integer");
            boolean changed=!old.equals(type);
            var converted=new LinkedHashMap<Node,Object>();
            for(Node object:repo.members(repo.owner(declaration))) {
                Node d=repo.descriptor(object,(String)declaration.getProperty("name"));if(d==null)continue;
                Object value=ValueCodec.decode(old,d.getProperty("value",null));
                if(value!=null && conversion.equals("floatToIntegerExact")) {
                    try {value=new java.math.BigDecimal(((Number)value).doubleValue()).longValueExact();}
                    catch(ArithmeticException e) {c.fail("TYPE_CONFLICT","Float is not an exact signed 64-bit integer");}
                }
                if(value!=null && conversion.equals("identity")&&!old.equals(type))c.fail("TYPE_CONFLICT","identity requires unchanged type for non-null values");
                converted.put(d,ValueCodec.storage(type,ValueCodec.normalize(type,value)));
            }
            if(changed) {
                repo.update(declaration,Map.of("type",type),c.actorId);
                for(var entry:converted.entrySet())repo.update(entry.getKey(),Json.map("encoding",Set.of("List","DDict").contains(type)?"json":"native","value",entry.getValue()),c.actorId);
            }
            return new Change(outcome(declaration),changed);
        });
    }
    private static String lower(String value) {return Character.toLowerCase(value.charAt(0))+value.substring(1);}
}
