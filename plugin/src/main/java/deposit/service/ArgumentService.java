package deposit.service;

import deposit.common.*;
import deposit.repository.CoreRepository;
import java.util.*;
import org.neo4j.graphdb.Node;

/** Validates the public argument list and resolves domain identities in its captured scope. */
public final class ArgumentService extends BaseService {
    private final Map<String,Object> bindings;
    private final boolean replayBindings;
    public ArgumentService(RequestContext c,Map<String,Object> bindings,boolean replayBindings){super(c);this.bindings=bindings;this.replayBindings=replayBindings;}
    public Map<String,Object> request(String procedure,List<Object> arguments){
        var spec=ProcedureCatalog.spec(procedure);
        if(arguments==null||arguments.size()!=spec.bindings().size())c.fail("VALIDATION_FAILED","Wrong number of domain arguments");
        var result=new LinkedHashMap<String,Object>();
        for(int i=0;i<arguments.size();i++){
            String field=spec.bindings().get(i);Object value=arguments.get(i);
            if(field.equals("+")){
                var map=ValueCodec.map(value);
                for(var entry:map.entrySet()){
                    if(result.containsKey(entry.getKey()))c.fail("VALIDATION_FAILED","Patch cannot replace a positional argument");
                    result.put(entry.getKey(),entry.getValue());
                }
            }else if(field.startsWith("@")){
                String[] parts=field.substring(1).split(":");String id=identity(parts[0],value,"arg"+i,procedure.equals("deposit.create.object"));
                if(id==null)result.put("className",name(value));else result.put(parts[1],id);
            }else if(field.startsWith("?"))result.putAll(selection(field.substring(1),value,"arg"+i));
            else if(field.startsWith("~")){if(value!=null)result.put(field.substring(1),value);}
            else result.put(field,value);
        }
        if(procedure.equals("deposit.apply.batch")){
            if(!replayBindings&&result.get("commands") instanceof List<?> commands){
                for(int index=0;index<commands.size();index++){
                    if(!(commands.get(index) instanceof Map<?,?> command)||!(command.get("procedure") instanceof String target)||!DomainService.COMMANDS.contains(target)||!(command.get("arguments") instanceof List<?> args))continue;
                    var fields=ProcedureCatalog.spec(target).bindings();
                    for(int i=0;i<Math.min(fields.size(),args.size());i++){
                        String field=fields.get(i);Object value=args.get(i);
                        if(!field.startsWith("@")||(value instanceof Map<?,?> map&&map.containsKey("ref")))continue;
                        try{identity(field.substring(1).split(":")[0],value,"command"+index+".arg"+i,target.equals("deposit.create.object"));}
                        catch(ExceptionHandler.DepositException failure){
                            // A preceding command may create this name; resolve it inside the batch.
                            if(!failure.getMessage().contains("\"code\":\"NOT_FOUND\""))throw failure;
                        }
                    }
                }
            }
            c.selectorBindings=bindings;
        }
        return result;
    }
    private String namespace(){
        String ns=c.state.scoped?c.state.namespace:c.session.namespace;
        if(ns==null)c.fail("NO_ACTIVE_NAMESPACE","Select a Namespace first");
        requireRole(ns,"viewer");return ns;
    }
    private String identity(String kind,Object selector,String path,boolean infer){
        if(replayBindings&&bindings.containsKey(path))return (String)bindings.get(path);
        String id=null;Node node=null;
        if(selector instanceof Map<?,?>){
            var map=ValueCodec.map(selector);
            if(map.containsKey("id")){fields(map,"id");id=RequestContext.uuid(map.get("id"));}
            else if(kind.equals("Descriptor")||kind.equals("ClassDescriptor")){
                String ownerKey=kind.equals("Descriptor")?"objectId":"class";fields(map,ownerKey,"name");
                String ownerId=kind.equals("Descriptor")?RequestContext.uuid(map.get(ownerKey)):identity("Class",map.get(ownerKey),path+".class",false);
                node=repo.find(kind,Map.of("namespaceId",namespace(),"ownerId",ownerId,"name",name(map.get("name"))));
            }else c.fail("VALIDATION_FAILED","Expected a name or {id: UUID}");
        }else if(Set.of("Class","UserTool","Namespace","User").contains(kind)){
            String field=kind.equals("User")?"username":"name";String name=name(selector);
            node=Set.of("Namespace","User").contains(kind)?repo.find(kind,field,name):repo.find(kind,Map.of("namespaceId",namespace(),field,name));
            if(node==null&&infer){bindings.put(path,null);return null;}
        }else id=RequestContext.uuid(selector);
        if(id!=null)node=Set.of("Namespace","User").contains(kind)?repo.find(kind,"id",id):repo.entity(kind,namespace(),id);
        if(node==null)c.fail("NOT_FOUND",kind+" not found");
        if(kind.equals("Namespace"))requireRole(CoreRepository.id(node),"viewer");
        id=CoreRepository.id(node);bindings.put(path,id);return id;
    }
    private Map<String,Object> selection(String kind,Object value,String path){
        if(value==null)return new LinkedHashMap<>();
        if(Set.of("Class","UserTool","Namespace","User").contains(kind)){
            if(value instanceof Map<?,?> map&&map.isEmpty())return new LinkedHashMap<>();
            return Json.map(kind.equals("User")?"userId":"id",identity(kind,value,path,false));
        }
        if(value instanceof String)return Json.map("id",RequestContext.uuid(value));
        var result=new LinkedHashMap<>(ValueCodec.map(value));
        for(String field:List.of("class","sourceClass","targetClass"))if(result.containsKey(field)){
            String target=field.equals("class")?"classId":field.equals("sourceClass")?"sourceId":"targetId";
            if(result.containsKey(target))c.fail("VALIDATION_FAILED","Conflicting selectors");
            result.put(target,identity("Class",result.remove(field),path+"."+field,false));
        }
        return result;
    }
}
