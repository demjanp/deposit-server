package deposit.service;

import deposit.common.*;
import deposit.common.Results.Outcome;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Ordered domain commands sharing a single action, transaction and receipt. */
public final class BatchService extends ActionService {
    public BatchService(RequestContext c) {super(c);}
    private record Command(String key,String procedure,List<String> fields,List<Object> arguments,Map<String,Object> descriptorTypes) {}
    public Outcome apply(Map<String,Object> request) {
        fields(request,"commands");
        Object input=request.getOrDefault("commands",List.of());
        if(!(input instanceof List<?>))c.fail("VALIDATION_FAILED","commands must be a list");
        List<?> list=(List<?>)input;
        if(list.size()>1000||Json.write(request).getBytes(StandardCharsets.UTF_8).length>8*1024*1024)c.fail("LIMIT_EXCEEDED","Batch exceeds 1000 commands or 8 MiB");
        var commands=new ArrayList<Command>();var keys=new HashSet<String>();String role="editor";
        for(int index=0;index<list.size();index++) {
            String key=null;
            try {
                var item=ValueCodec.map(list.get(index));fields(item,"key","procedure","arguments","descriptorTypes");key=string(item.get("key"));
                if(key.isBlank()||!keys.add(key))c.fail("VALIDATION_FAILED","Command keys must be unique and nonempty");
                String procedure=string(item.get("procedure"));if(!DomainService.COMMANDS.contains(procedure))c.fail("VALIDATION_FAILED","Command is not allowed in a batch");
                if(DomainService.commandRole(procedure).equals("owner"))role="owner";
                if(!(item.get("arguments") instanceof List<?>))c.fail("VALIDATION_FAILED","Command arguments must be a list");
                @SuppressWarnings("unchecked") List<Object> arguments=(List<Object>)item.get("arguments");
                var commandFields=ProcedureCatalog.spec(procedure).bindings();
                if(arguments.size()!=commandFields.size())c.fail("VALIDATION_FAILED","Wrong number of command arguments");
                commands.add(new Command(key,procedure,commandFields,arguments,ValueCodec.map(item.getOrDefault("descriptorTypes",Map.of()))));
            }catch(ExceptionHandler.DepositException failure) {throw commandError(failure,key,index);}
        }
        return action("deposit.apply.batch",request,false,role,()->{
            var results=new LinkedHashMap<String,Object>();boolean changed=false;var domain=new DomainService(c);
            for(int index=0;index<commands.size();index++) {
                Command command=commands.get(index);
                try {
                    c.state.kernel.assertOpen();
                    // Earlier commands may have deleted, renamed or recreated an entity.
                    c.clearResolved();
                    var arguments=new ArrayList<>(command.arguments());
                    var fields=command.fields();
                    for(int argument=0;argument<arguments.size();argument++) {
                        String field=fields.get(argument);
                        if(!field.startsWith("@")&&!Set.of("id","objectId","classId","sourceId","targetId").contains(field))continue;
                        Object value=arguments.get(argument);if(!(value instanceof Map<?,?> map)||!map.containsKey("ref"))continue;
                        var reference=ValueCodec.map(value);fields(reference,"ref","field");
                        if(!"id".equals(reference.get("field")))c.fail("VALIDATION_FAILED","Reference field must be id");
                        String key=string(reference.get("ref"));Object previous=results.get(key);
                        if(!(previous instanceof Map<?,?> data)||!(data.get("id") instanceof String))c.fail("VALIDATION_FAILED","Reference must name an earlier entity result");
                        Object id=((Map<?,?>)previous).get("id");arguments.set(argument,field.startsWith("@")?Json.map("id",id):id);
                    }
                    var bindings=new LinkedHashMap<String,Object>();String prefix="command"+index+".";
                    c.selectorBindings.forEach((key,value)->{if(key.startsWith(prefix))bindings.put(key.substring(prefix.length()),value);});
                    var resolved=new ArgumentService(c,bindings,true).bind(command.procedure(),fields,arguments);
                    applyDescriptorTypes(command,resolved);
                    Change step=domain.authorizedCommand(command.procedure(),resolved);changed|=step.changed();
                    if(c.journal!=null)c.journal.checkpoint(new deposit.repository.HistoryRepository(c.tx,repo).capture(c.namespaceId));
                    var saved=Json.read(Json.write(step.outcome().data()));if(!step.changed())saved.put("changed",false);
                    results.put(command.key(),saved);
                }catch(ExceptionHandler.DepositException failure) {throw commandError(failure,command.key(),index);}
            }
            return new Change(new Outcome(null,null,Json.map("results",results)),changed);
        });
    }
    private void applyDescriptorTypes(Command command, Map<String,Object> request) {
        var types=command.descriptorTypes();
        if(types.isEmpty())return;
        switch(command.procedure()) {
            case "deposit.create.descriptor","deposit.set.descriptor" -> {
                String name=string(request.get("name"));
                if(!types.keySet().equals(Set.of(name))||!request.containsKey("value"))
                    c.fail("VALIDATION_FAILED","Explicit type must identify the Descriptor being written");
                request.put("value",new DomainService.ExplicitDescriptorValue(ValueCodec.type(types.get(name)),request.get("value")));
            }
            case "deposit.create.object","deposit.set.object" -> {
                var values=new LinkedHashMap<>(ValueCodec.map(request.get("descriptors")));
                if(!values.keySet().containsAll(types.keySet()))
                    c.fail("VALIDATION_FAILED","Explicit types must identify supplied Descriptor values");
                types.forEach((name,type)->values.put(name,new DomainService.ExplicitDescriptorValue(ValueCodec.type(type),values.get(name))));
                request.put("descriptors",values);
            }
            default -> c.fail("VALIDATION_FAILED","Command does not accept explicit Descriptor types");
        }
    }

    private RuntimeException commandError(ExceptionHandler.DepositException failure,String key,int index) {
        var error=Json.read(failure.getMessage().substring("DEPOSIT_ERROR ".length()));var details=new LinkedHashMap<>(ValueCodec.map(error.get("details")));
        details.put("commandKey",key);details.put("commandIndex",index);
        return ExceptionHandler.error((String)error.get("code"),(String)error.get("message"),c,Boolean.TRUE.equals(error.get("retryable")),details);
    }
}
