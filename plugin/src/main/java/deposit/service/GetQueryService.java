package deposit.service;

import deposit.common.*;
import deposit.common.Results.*;
import deposit.repository.GetQueryRepository;
import deposit.service.query.*;
import deposit.service.query.QueryExecution.Row;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;
import org.neo4j.graphdb.Node;

/** Authenticated, revision-bound query lifetime; repository owns the graph cursor. */
public final class GetQueryService extends BaseService {
    private static final ScheduledThreadPoolExecutor DEADLINES;
    static {DEADLINES=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"deposit-query-deadlines");t.setDaemon(true);return t;});DEADLINES.setRemoveOnCancelPolicy(true);}
    private long deadline;private final AtomicBoolean expired=new AtomicBoolean();private ScheduledFuture<?> timer;
    private QueryTranslator.QueryPlan plan;private GetQueryRepository repository;
    public GetQueryService(RequestContext c){super(c);}
    private void check(){if(expired.get()||System.nanoTime()>deadline)c.fail("LIMIT_EXCEEDED","Query timeout exceeded");c.state.kernel.assertOpen();authenticate();requireRole(c.namespaceId,"viewer");long revision=repo.sequence(c.namespaceId);if(revision!=c.revision)c.fail("REVISION_CONFLICT","Revision changed during query");}
    private void prepare(Map<String,Object> request,boolean page){fields(request,page?new String[]{"query","parameters","maxHops","timeoutMs","pageSize","cursor"}:new String[]{"query","parameters","maxHops","timeoutMs"});readContext();scoped("viewer");
        if(page&&request.containsKey("cursor")){var cursor=CursorCodec.decode(c,string(request.get("cursor")));if(!c.namespaceId.equals(cursor.get("scope")))c.fail("SCOPE_MISMATCH","Cursor belongs to another Namespace");if(!c.actorId.equals(cursor.get("actor")))c.fail("VALIDATION_FAILED","Cursor belongs to another actor");if(((Number)cursor.get("revision")).longValue()!=c.revision)c.fail("REVISION_CONFLICT","Cursor revision changed");}
        long timeout=integer(request.getOrDefault("timeoutMs",30000L)),hops=integer(request.getOrDefault("maxHops",3L));if(timeout<1||timeout>30000||hops<1||hops>3)c.fail("LIMIT_EXCEEDED","Invalid timeoutMs or automatic maxHops");
        deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);timer=DEADLINES.schedule(()->{expired.set(true);c.tx.terminate();},timeout,TimeUnit.MILLISECONDS);
        try{repository=new GetQueryRepository(c);var parsed=QueryParser.parse(string(request.get("query")),this::check);plan=QueryTranslator.translateToCypher(parsed,c.namespaceId,(int)hops,repository::schema,request.containsKey("parameters")?ValueCodec.map(request.get("parameters")):Map.of(),this::check);check();}catch(RuntimeException e){timer.cancel(false);throw e;}
    }
    private Stream<Row> execute(){try{var source=new QueryExecution(plan,this::check).execute(repository.execute(plan,this::check));var iterator=source.iterator();return StreamSupport.stream(new Spliterators.AbstractSpliterator<Row>(Long.MAX_VALUE,Spliterator.ORDERED){public boolean tryAdvance(java.util.function.Consumer<? super Row> consumer){try{check();if(!iterator.hasNext())return false;consumer.accept(iterator.next());check();return true;}catch(RuntimeException e){if(expired.get())c.fail("LIMIT_EXCEEDED","Query timeout exceeded");throw e;}}},false).onClose(()->{try{source.close();}finally{timer.cancel(false);}check();});}catch(RuntimeException e){timer.cancel(false);if(expired.get())c.fail("LIMIT_EXCEEDED","Query timeout exceeded");throw e;}}
    private Map<String,Object> values(Row row,boolean portable){var data=new LinkedHashMap<String,Object>();for(int i=0;i<row.cells().size();i++){check();Object v=row.cells().get(i).value();data.put(plan.projections.get(i).name(),portable&&v instanceof Node n?repository.portable(n):v);}return data;}
    private Map<String,Object> presence(Row row){var presence=new LinkedHashMap<String,Object>();for(int i=0;i<row.cells().size();i++)presence.put(plan.projections.get(i).name(),row.cells().get(i).present());return presence;}
    public Stream<QueryRowResult> getQuery(Map<String,Object> request){prepare(request,false);return execute().map(row->new QueryRowResult(values(row,false),plan.columns(),presence(row),c.meta()));}
    public DataRow getQueryPage(Map<String,Object> request){prepare(request,true);try{
        long size=integer(request.getOrDefault("pageSize",200L));if(size<1||size>1000)c.fail("LIMIT_EXCEEDED","pageSize must be between 1 and 1000");
        var logical=new LinkedHashMap<>(request);logical.remove("pageSize");logical.remove("cursor");logical.putIfAbsent("parameters",Map.of());logical.putIfAbsent("maxHops",3L);logical.putIfAbsent("timeoutMs",30000L);String hash=Json.hash(Json.write(logical));long offset=0;
        if(request.containsKey("cursor")){var cursor=CursorCodec.decode(c,string(request.get("cursor")));if(!c.namespaceId.equals(cursor.get("scope")))c.fail("SCOPE_MISMATCH","Cursor belongs to another Namespace");if(!c.actorId.equals(cursor.get("actor"))||!hash.equals(cursor.get("hash")))c.fail("VALIDATION_FAILED","Cursor does not match query");if(((Number)cursor.get("revision")).longValue()!=c.revision)c.fail("REVISION_CONFLICT","Cursor revision changed");offset=((Number)cursor.get("offset")).longValue();if(offset<0)c.fail("VALIDATION_FAILED","Invalid cursor offset");}
        var rows=new ArrayList<Map<String,Object>>();boolean more;try(var stream=execute()){var it=stream.iterator();for(long i=0;i<offset;i++){if(!it.hasNext())c.fail("VALIDATION_FAILED","Invalid cursor offset");it.next();}while(rows.size()<size&&it.hasNext()){var row=it.next();rows.add(Json.map("values",values(row,true),"presence",presence(row)));}more=it.hasNext();check();}
        var meta=c.meta();if(more)meta.put("nextCursor",CursorCodec.encode(c,Json.map("scope",c.namespaceId,"actor",c.actorId,"hash",hash,"revision",c.revision,"offset",offset+rows.size(),"expires",System.currentTimeMillis()+900000L)));return new DataRow(Json.map("columns",plan.columns(),"rows",rows),meta);
    }finally{timer.cancel(false);}}
}
