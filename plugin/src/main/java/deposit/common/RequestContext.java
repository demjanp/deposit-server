package deposit.common;

import java.util.*;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;

/** One invocation's validated envelope; transaction state lives in ServerContext. */
public final class RequestContext {
    public final Transaction tx;
    public final java.time.ZonedDateTime timestamp=java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC);
    public final String requestId, token;
    public String operationId;
    public Long expectedRevision;
    public boolean publicCall, replayed, operational;
    public String procedure, inputHash, actionScope;
    public Map<String,Object> request;
    public Map<String,Object> selectorBindings=Map.of();
    public long observationGeneration;
    public final ServerContext server;
    public final ServerContext.TxState state;
    public ServerContext.Session session;
    public String actorId, namespaceId;
    public long revision;
    public ChangeSet journal;
    public String historyKind="edit",historyLink,historyTarget;
    public RequestContext(Transaction tx,Map<String,Object> context) {
        this.tx=tx;
        if(context==null)throw ExceptionHandler.fail("VALIDATION_FAILED","context must be a map");
        for(String key:context.keySet()) if(!Set.of("token","requestId","operationId","expectedRevision").contains(key)) throw ExceptionHandler.fail("VALIDATION_FAILED","Unknown context field");
        requestId=uuid(context.getOrDefault("requestId",UUID.randomUUID().toString()));
        operationId=context.containsKey("operationId")?uuid(context.get("operationId")):null;
        token=context.get("token") instanceof String s?s:null;
        Object expected=context.get("expectedRevision");
        if(expected!=null && (!(expected instanceof Long || expected instanceof Integer)||((Number)expected).longValue()<0)) throw ExceptionHandler.fail("VALIDATION_FAILED","Invalid expectedRevision");
        expectedRevision=expected==null?null:((Number)expected).longValue();
        var internal=(InternalTransaction)tx;
        server=ServerContext.forDatabase(internal.getDatabaseId());
        state=server.admit(internal.kernelTransaction());
    }
    public static String uuid(Object value) {
        if(!(value instanceof String s)) throw ExceptionHandler.fail("VALIDATION_FAILED","Expected UUID");
        try {String canonical=UUID.fromString(s).toString();if(!canonical.equalsIgnoreCase(s)) throw new IllegalArgumentException(); return canonical;}
        catch(IllegalArgumentException e) {throw ExceptionHandler.fail("VALIDATION_FAILED","Invalid UUID");}
    }
    public Map<String,Object> meta() {return Json.map("namespaceId",namespaceId,"revision",revision,"operationId",operationId,"requestId",requestId,"nextCursor",null);}
    public void fail(String code,String message) {throw ExceptionHandler.error(code,message,this,false,Map.of());}
}
