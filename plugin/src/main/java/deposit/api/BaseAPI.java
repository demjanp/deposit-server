package deposit.api;

import deposit.common.*;
import org.neo4j.graphdb.Transaction;
import org.neo4j.procedure.Context;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public abstract class BaseAPI {
    protected Stream<Object> invokePublic(String token,String procedure,List<Object> arguments) {
        return invokePublic(token,procedure,arguments,null);
    }
    protected Stream<Object> invokePublic(String token,String procedure,List<Object> arguments,Object operationId) {
        return invoke(token==null?Map.of():Map.of("token",token),c->{
            c.nativeResultsOnly=true;
            var actions=new deposit.service.SessionActionService(c);
            actions.beginPublic(procedure,arguments,operationId);
            Stream<?> result=actions.finish(new deposit.service.ProcedureService(c).execute());
            actions.observe();
            return result.map(row->(Object)row);
        });
    }
    @Context public Transaction tx;
    @Context public org.neo4j.logging.Log log;
    protected RequestContext context(java.util.Map<String,Object> context) {return new RequestContext(tx,context);}
    protected <T> Stream<T> invoke(Map<String,Object> context,Function<RequestContext,Stream<T>> action) {
        RequestContext c;
        try {c=context(context);}catch(RuntimeException failure) {throw safe(failure,null);}
        final Stream<T> source;
        try {source=action.apply(c);}catch(RuntimeException failure) {throw safe(failure,c);}
        if(c.state.mutation&&c.state.privileged&&!c.state.auditRegistered) {
            c.state.auditRegistered=true;
            String audit="Deposit committed privileged action actor="+c.actorId+" namespace="+c.state.namespace+" administrativeMode="+c.state.administrative+" operationId="+c.operationId+" requestId="+c.requestId;
            c.state.afterCommit.add(()->log.info(audit));
        }
        var iterator=source.iterator();
        return StreamSupport.stream(new Spliterators.AbstractSpliterator<T>(Long.MAX_VALUE,Spliterator.ORDERED) {
            @Override public boolean tryAdvance(Consumer<? super T> consumer) {
                try {if(!iterator.hasNext()) {source.close();return false;}consumer.accept(iterator.next());return true;}
                catch(RuntimeException failure) {try {source.close();}catch(RuntimeException ignored){}throw safe(failure,c);}
            }
        },false).onClose(()->{try {source.close();}catch(RuntimeException failure) {throw safe(failure,c);}});
    }
    private RuntimeException safe(RuntimeException failure,RequestContext c) {
        tx.terminate();
        if(failure instanceof ExceptionHandler.DepositException) {
            var error=Json.read(failure.getMessage().substring("DEPOSIT_ERROR ".length()));
            return ExceptionHandler.error((String)error.get("code"),(String)error.get("message"),c,Boolean.TRUE.equals(error.get("retryable")),ValueCodec.map(error.get("details")));
        }
        if(failure instanceof StorageAdmission.StoragePressureException)
            return ExceptionHandler.error("LIMIT_EXCEEDED",failure.getMessage(),c,false,Map.of());
        if(failure instanceof org.neo4j.graphdb.TransactionTerminatedException) return ExceptionHandler.error("CANCELLED","Transaction terminated",c,false,Map.of());
        String reference=UUID.randomUUID().toString();log.error("Deposit internal failure reference="+reference+" requestId="+(c==null?"unknown":c.requestId)+" exception="+failure.getClass().getName());
        return ExceptionHandler.error("INTERNAL_ERROR","Internal error; consult restricted diagnostics",c,false,Map.of("diagnosticReference",reference));
    }
}
