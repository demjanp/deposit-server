package deposit.service;

import deposit.common.*;
import deposit.common.Results.Outcome;
import java.util.*;
import org.neo4j.graphdb.*;

public final class DomainService extends ActionService {
    private final DomainRelationshipCommands relationships;
    private final DomainSchemaCommands schema;
    public DomainService(RequestContext c) {super(c);relationships=new DomainRelationshipCommands(c);schema=new DomainSchemaCommands(this,c);}
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
        if(old==null){Node descriptor=repo.attach("Descriptor",object,c.actorId,Json.map("name",name));repo.writeValue(descriptor,type,normalized,c.actorId);return new DescriptorWrite(descriptor,true);}
        return new DescriptorWrite(old,repo.writeValue(old,type,normalized,c.actorId));
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
    public Outcome create(String kind,Map<String,Object> request) {return action("deposit.create."+lower(kind),request,false,"editor",()->createCommand(kind,request));}
    private Change createCommand(String kind,Map<String,Object> request) {
        switch(kind) {
            case "Class" -> fields(request,"name","descriptors","position","order","comment");
            case "Object" -> fields(request,"classId","className","descriptors","position");
            case "ClassDescriptor" -> fields(request,"classId","name","type","order","comment");
            case "Descriptor" -> fields(request,"objectId","name","value");
            case "UserTool" -> fields(request,"name","data","comment");
            case "Pattern" -> fields(request,"name","definition","comment");
            default -> throw new IllegalArgumentException();
        }
            Node n;
            switch(kind) {
                case "Class" -> n=newClass(request);
                case "Object" -> {
                    Node clazz=clazz(request,true);n=repo.create("Object",c.namespaceId,c.actorId,Map.of("position",position(request.getOrDefault("position",List.of(0.0,0.0)))));
                    repo.assign(n,clazz,c.actorId);descriptors(n,request);
                }
                case "ClassDescriptor" -> n=newDeclaration(required("Class",request.get("classId")),request);
                case "Descriptor" -> {if(!request.containsKey("value"))c.fail("VALIDATION_FAILED","value required");n=descriptor(required("Object",request.get("objectId")),name(request.get("name")),request.get("value"),true).descriptor();}
                case "UserTool" -> {
                    String toolName=name(request.get("name")),data=string(request.get("data"));
                    if(repo.find("UserTool",Map.of("namespaceId",c.namespaceId,"name",toolName))!=null)c.fail("DUPLICATE","User Tool name exists");
                    n=repo.create("UserTool",c.namespaceId,c.actorId,Json.map("name",toolName,"data",data,"comment",string(request.getOrDefault("comment",""))));
                }
                case "Pattern" -> {
                    String patternName=name(request.get("name"));
                    Map<String,Object> definition=PatternService.normalizeDefinition(ValueCodec.map(request.get("definition")));
                    n=repo.create("Pattern",c.namespaceId,c.actorId,Json.map("name",patternName,"definition",Json.write(definition),"comment",string(request.getOrDefault("comment",""))));
                }
                default -> throw new IllegalArgumentException();
            }
            return new Change(outcome(n),true);
    }
    public Outcome set(String kind,Map<String,Object> request) {return action("deposit.set."+lower(kind),request,false,"editor",()->setCommand(kind,request));}
    Change setCommand(String kind,Map<String,Object> request) {
        switch(kind) {
            case "Class" -> fields(request,"id","name","position","order","comment");
            case "Object" -> fields(request,"id","position","descriptors","clearDescriptors");
            case "ClassDescriptor" -> fields(request,"id","order","comment");
            case "Descriptor" -> fields(request,"objectId","name","value");
            case "UserTool" -> fields(request,"id","name","data","comment");
            case "Pattern" -> fields(request,"id","name","definition","comment");
            default -> throw new IllegalArgumentException();
        }
            if(kind.equals("Descriptor")) {if(!request.containsKey("value"))c.fail("VALIDATION_FAILED","value required");var result=descriptor(required("Object",request.get("objectId")),name(request.get("name")),request.get("value"),false);return new Change(outcome(result.descriptor()),result.changed());}
            Node n=required(kind,request.get("id"));var updates=new LinkedHashMap<String,Object>();
            if(request.containsKey("name")) {
                String name=name(request.get("name"));Node found=repo.find(kind,Map.of("namespaceId",c.namespaceId,"name",name));if(found!=null&&!found.equals(n))c.fail("DUPLICATE","Name exists");updates.put("name",name);
            }
            if(request.containsKey("position"))updates.put("position",position(request.get("position")));
            if(request.containsKey("order"))updates.put("order",integer(request.get("order")));
            if(request.containsKey("comment"))updates.put("comment",string(request.get("comment")));
            if(request.containsKey("data"))updates.put("data",string(request.get("data")));
            if(kind.equals("Pattern")&&request.containsKey("definition"))updates.put("definition",Json.write(PatternService.normalizeDefinition(ValueCodec.map(request.get("definition")))));
            String previousClassName=kind.equals("Class")?(String)n.getProperty("name",""):null;
            boolean changed=repo.update(n,updates,c.actorId);
            if(kind.equals("Class")&&updates.containsKey("name")&&!Objects.equals(previousClassName,updates.get("name")))changed=renamePatternReferences(previousClassName,(String)updates.get("name"))||changed;
            if(kind.equals("Object")&&descriptors(n,request)) {repo.audit(n,c.actorId);changed=true;}
            return new Change(outcome(n),changed);
    }
    private boolean renamePatternReferences(String oldName,String newName){
        boolean changed=false;for(Node pattern:repo.nodes("Pattern",Map.of("namespaceId",c.namespaceId))){Map<String,Object> definition=Json.read((String)pattern.getProperty("definition"));Map<String,Object> objects=ValueCodec.map(definition.get("objects"));boolean edit=false;for(Object raw:objects.values()){var role=ValueCodec.map(raw);if(oldName.equals(role.get("className"))){role.put("className",newName);edit=true;}}if(edit){repo.update(pattern,Map.of("definition",Json.write(definition)),c.actorId);changed=true;}}return changed;
    }
    public Outcome remove(String kind,Map<String,Object> request) {return action("deposit.delete."+lower(kind),request,false,"editor",()->removeCommand(kind,request));}
    private Change removeCommand(String kind,Map<String,Object> request) {
        fields(request,"id");
            Node n=required(kind,request.get("id"));
            if(kind.equals("Class")&&!repo.members(n).isEmpty())c.fail("VALIDATION_FAILED","Class has members");
            if(kind.equals("ClassDescriptor"))for(Node object:repo.members(repo.owner(n)))if(repo.descriptor(object,(String)n.getProperty("name"))!=null)c.fail("VALIDATION_FAILED","Declaration is populated");
            var deleted=new ArrayList<String>();repo.remove(n,deleted);return new Change(new Outcome(null,null,Json.map("deletedIds",deleted)),true);
    }
    public Outcome membership(Map<String,Object> request) {return action("deposit.set.membership",request,false,"editor",()->membershipCommand(request));}
    private Change membershipCommand(Map<String,Object> request) {return relationships.membership(request);}
    public Outcome convert(Map<String,Object> request) {return action("deposit.set.classDescriptorType",request,false,"editor",()->convertCommand(request));}
    Change convertCommand(Map<String,Object> request) {
        fields(request,"id","type","conversion");
            Node declaration=required("ClassDescriptor",request.get("id"));String old=(String)declaration.getProperty("type"),type=ValueCodec.type(request.get("type")),conversion=string(request.get("conversion"));
            if(!Set.of("identity","integerToFloat","floatToIntegerExact").contains(conversion))c.fail("VALIDATION_FAILED","Invalid conversion");
            if(conversion.equals("integerToFloat")&&!(old.equals("Integer")&&type.equals("Float")))c.fail("TYPE_CONFLICT","Conversion requires Integer to Float");
            if(conversion.equals("floatToIntegerExact")&&!(old.equals("Float")&&type.equals("Integer")))c.fail("TYPE_CONFLICT","Conversion requires Float to Integer");
            boolean changed=!old.equals(type);
            var converted=new LinkedHashMap<Node,Object>();
            for(Node object:repo.members(repo.owner(declaration))) {
                Node d=repo.descriptor(object,(String)declaration.getProperty("name"));if(d==null)continue;
                Object value=repo.descriptorValue(d);
                if(value!=null && conversion.equals("floatToIntegerExact")) {
                    try {value=new java.math.BigDecimal(((Number)value).doubleValue()).longValueExact();}
                    catch(ArithmeticException e) {c.fail("TYPE_CONFLICT","Float is not an exact signed 64-bit integer");}
                }
                if(value!=null && conversion.equals("identity")&&!old.equals(type))c.fail("TYPE_CONFLICT","identity requires unchanged type for non-null values");
                converted.put(d,ValueCodec.normalize(type,value));
            }
            if(changed) {
                repo.update(declaration,Map.of("type",type),c.actorId);
                for(var entry:converted.entrySet())repo.writeValue(entry.getKey(),type,entry.getValue(),c.actorId);
            }
            return new Change(outcome(declaration),changed);
    }
    private static String lower(String value) {return Character.toLowerCase(value.charAt(0))+value.substring(1);}

    public static final Set<String> COMMANDS;
    static {
        var names=new HashSet<String>();
        for(String entity:List.of("class","object","descriptor","relationship","classRelationship","userTool","pattern"))
            for(String verb:List.of("create","set","delete"))names.add("deposit."+verb+"."+entity);
        names.addAll(List.of("deposit.create.classDescriptor","deposit.delete.classDescriptor","deposit.set.classDescriptor","deposit.set.membership","deposit.set.namespace","deposit.delete.all"));
        COMMANDS=Set.copyOf(names);
    }
    public static String commandRole(String procedure) {return Set.of("deposit.set.namespace","deposit.delete.all").contains(procedure)?"owner":"editor";}
    public Outcome execute(String procedure,Map<String,Object> request) {
        if(!COMMANDS.contains(procedure))c.fail("VALIDATION_FAILED","Unsupported domain command");
        return action(procedure,request,false,commandRole(procedure),()->command(procedure,request));
    }
    Change command(String procedure,Map<String,Object> request) {
        c.state.kernel.assertOpen();requireRole(c.namespaceId,commandRole(procedure));
        return switch(procedure) {
            case "deposit.set.classDescriptor" -> schema.declarationPatch(request);
            case "deposit.set.membership" -> membershipCommand(request);
            case "deposit.set.classDescriptorType" -> convertCommand(request);
            case "deposit.set.classDescriptorOrder","deposit.set.classDescriptorComment" -> {
                String field=procedure.endsWith("Order")?"order":"comment";fields(request,"id",field);
                if(!request.containsKey(field))c.fail("VALIDATION_FAILED",field+" required");
                yield setCommand("ClassDescriptor",request);
            }
            case "deposit.set.namespace" -> schema.namespace(request);
            case "deposit.delete.all" -> schema.clear(request);
            default -> {
                if(!COMMANDS.contains(procedure))c.fail("VALIDATION_FAILED","Unsupported domain command");
                String[] parts=procedure.split("\\.");String kind=Character.toUpperCase(parts[2].charAt(0))+parts[2].substring(1);
                if(kind.equals("Relationship")||kind.equals("ClassRelationship"))yield relationships.relationship(parts[1],kind,request);
                yield switch(parts[1]) {case "create"->createCommand(kind,request);case "set"->setCommand(kind,request);case "delete"->removeCommand(kind,request);default->throw new IllegalArgumentException();};
            }
        };
    }
}
