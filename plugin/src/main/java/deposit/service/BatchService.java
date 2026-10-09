package deposit.service;

import deposit.common.*;
import deposit.common.Results.Outcome;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Ordered domain commands sharing a single action, transaction and receipt. */
public final class BatchService extends ActionService {
    public BatchService(RequestContext c) {super(c);}
    private record Command(String key,String procedure,Map<String,Object> request) {}
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
                var item=ValueCodec.map(list.get(index));fields(item,"key","procedure","request");key=string(item.get("key"));
                if(key.isBlank()||!keys.add(key))c.fail("VALIDATION_FAILED","Command keys must be unique and nonempty");
                String procedure=string(item.get("procedure"));if(!DomainService.COMMANDS.contains(procedure))c.fail("VALIDATION_FAILED","Command is not allowed in a batch");
                if(DomainService.commandRole(procedure).equals("owner"))role="owner";
                commands.add(new Command(key,procedure,ValueCodec.map(item.get("request"))));
            }catch(ExceptionHandler.DepositException failure) {throw commandError(failure,key,index);}
        }
        return action("deposit.apply.batch",request,false,role,()->{
            var results=new LinkedHashMap<String,Object>();boolean changed=false;var domain=new DomainService(c);
            for(int index=0;index<commands.size();index++) {
                Command command=commands.get(index);
                try {
                    c.state.kernel.assertOpen();var resolved=new LinkedHashMap<>(command.request());
                    for(String field:List.of("id","objectId","classId","sourceId","targetId")) {
                        Object value=resolved.get(field);if(!(value instanceof Map<?,?>))continue;
                        var reference=ValueCodec.map(value);fields(reference,"ref","field");
                        if(!"id".equals(reference.get("field")))c.fail("VALIDATION_FAILED","Reference field must be id");
                        String key=string(reference.get("ref"));Object previous=results.get(key);
                        if(!(previous instanceof Map<?,?> data)||!(data.get("id") instanceof String))c.fail("VALIDATION_FAILED","Reference must name an earlier entity result");
                        resolved.put(field,((Map<?,?>)previous).get("id"));
                    }
                    Change step=domain.command(command.procedure(),resolved);changed|=step.changed();
                    if(c.journal!=null)c.journal.checkpoint(new deposit.repository.HistoryRepository(c.tx,repo).capture(c.namespaceId));
                    var saved=Json.read(Json.write(step.outcome().data()));if(!step.changed())saved.put("changed",false);
                    results.put(command.key(),saved);
                }catch(ExceptionHandler.DepositException failure) {throw commandError(failure,command.key(),index);}
            }
            return new Change(new Outcome(null,null,Json.map("results",results)),changed);
        });
    }
    private RuntimeException commandError(ExceptionHandler.DepositException failure,String key,int index) {
        var error=Json.read(failure.getMessage().substring("DEPOSIT_ERROR ".length()));var details=new LinkedHashMap<>(ValueCodec.map(error.get("details")));
        details.put("commandKey",key);details.put("commandIndex",index);
        return ExceptionHandler.error((String)error.get("code"),(String)error.get("message"),c,Boolean.TRUE.equals(error.get("retryable")),details);
    }
}
