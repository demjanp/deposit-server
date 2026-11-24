package deposit.service;

import deposit.common.*;
import deposit.common.Results.*;
import deposit.repository.GetQueryRepository;
import deposit.service.query.*;
import deposit.service.query.QueryExecution.Row;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.*;
import org.neo4j.graphdb.Node;

/** Authenticated, revision-bound query lifetime; repository owns the graph cursor. */
public final class GetQueryService extends BaseService {
    private static final ScheduledThreadPoolExecutor DEADLINES;
    static {DEADLINES=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"deposit-query-deadlines");t.setDaemon(true);return t;});DEADLINES.setRemoveOnCancelPolicy(true);}
    private long deadline;
    private final AtomicBoolean expired=new AtomicBoolean();
    private ScheduledFuture<?> timer;
    private QueryTranslator.QueryPlan plan;
    private GetQueryRepository repository;

    public GetQueryService(RequestContext c){super(c);}

    private void checkLive() {
        if(expired.get()||System.nanoTime()>deadline)c.fail("LIMIT_EXCEEDED","Query timeout exceeded");
        c.state.kernel.assertOpen();
    }
    private void checkQueryRevision() {
        checkLive();
        if(currentReadRevision("viewer")!=c.revision)c.fail("REVISION_CONFLICT","Revision changed during query");
    }
    private void startRead(Map<String,Object> request,boolean page,Long pinnedRevision) {
        fields(request,page?new String[]{"query","parameters","maxHops","timeoutMs","pageSize","cursor"}:
            new String[]{"query","parameters","maxHops","timeoutMs"});
        readContext();
        pinReadScope("viewer",pinnedRevision);
        if(pinnedRevision!=null&&(pinnedRevision!=c.revision||currentReadRevision("viewer")!=pinnedRevision))
            c.fail("REVISION_CONFLICT","Revision changed during query export");
    }
    private void prepare(Map<String,Object> request) {
        long timeout=integer(request.getOrDefault("timeoutMs",30000L)),hops=integer(request.getOrDefault("maxHops",3L));
        if(timeout<1||timeout>30000||hops<1||hops>3)c.fail("LIMIT_EXCEEDED","Invalid timeoutMs or automatic maxHops");
        deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
        timer=DEADLINES.schedule(()->{expired.set(true);c.tx.terminate();},timeout,TimeUnit.MILLISECONDS);
        try {
            repository=new GetQueryRepository(c);
            var parsed=QueryParser.parse(string(request.get("query")),this::checkLive);
            plan=QueryTranslator.translateToCypher(parsed,c.namespaceId,(int)hops,repository::schema,
                request.containsKey("parameters")?ValueCodec.map(request.get("parameters")):Map.of(),this::checkLive);
            checkLive();
        } catch(RuntimeException failure) {
            timer.cancel(false);
            if(expired.get())c.fail("LIMIT_EXCEEDED","Query timeout exceeded");
            throw failure;
        }
    }
    private Stream<Row> execute(long offset) {
        return new QueryExecution(plan,this::checkLive).execute(repository.execute(plan.executionPlan(),this::checkLive),offset);
    }
    private Map<String,Object> values(Row row,boolean portable) {
        var data=new LinkedHashMap<String,Object>();
        for(int i=0;i<row.cells().size();i++) {
            checkLive();
            Object value=row.cells().get(i).value();
            data.put(plan.projections.get(i).name(),portable&&value instanceof Node node?repository.portable(node):value);
        }
        return data;
    }
    private Map<String,Object> presence(Row row) {
        var presence=new LinkedHashMap<String,Object>();
        for(int i=0;i<row.cells().size();i++) {
            checkLive();
            presence.put(plan.projections.get(i).name(),row.cells().get(i).present());
        }
        return presence;
    }
    public Stream<QueryRowResult> getQuery(Map<String,Object> request){return getQuery(request,null);}
    Stream<QueryRowResult> getQuery(Map<String,Object> request,Long pinnedRevision) {
        startRead(request,false,pinnedRevision);
        prepare(request);
        try {
            var source=execute(0);
            var iterator=source.iterator();
            return StreamSupport.stream(new Spliterators.AbstractSpliterator<QueryRowResult>(Long.MAX_VALUE,Spliterator.ORDERED){
                @Override public boolean tryAdvance(Consumer<? super QueryRowResult> consumer) {
                    try {
                        checkQueryRevision();
                        if(!iterator.hasNext()) {checkQueryRevision();return false;}
                        var row=iterator.next();
                        consumer.accept(new QueryRowResult(values(row,false),plan.columns(),presence(row),c.meta()));
                        checkQueryRevision();
                        return true;
                    } catch(RuntimeException failure) {
                        if(expired.get())c.fail("LIMIT_EXCEEDED","Query timeout exceeded");
                        throw failure;
                    }
                }
            },false).onClose(()->{
                try {source.close();checkQueryRevision();}
                finally {timer.cancel(false);}
            });
        } catch(RuntimeException failure) {
            timer.cancel(false);
            if(expired.get())c.fail("LIMIT_EXCEEDED","Query timeout exceeded");
            throw failure;
        }
    }
    public DataRow getQueryPage(Map<String,Object> request){return getQueryPage(request,null);}
    DataRow getQueryPage(Map<String,Object> request,Long pinnedRevision) {
        startRead(request,true,pinnedRevision);
        long size=integer(request.getOrDefault("pageSize",200L));
        if(size<1||size>1000)c.fail("LIMIT_EXCEEDED","pageSize must be between 1 and 1000");
        var logical=new LinkedHashMap<>(request);
        logical.remove("pageSize");
        logical.remove("cursor");
        logical.putIfAbsent("parameters",Map.of());
        logical.putIfAbsent("maxHops",3L);
        logical.putIfAbsent("timeoutMs",30000L);
        String hash=Json.hash(Json.write(logical));
        long offset=0;
        if(request.containsKey("cursor")) {
            var cursor=CursorCodec.decode(c,string(request.get("cursor")));
            if(!c.namespaceId.equals(cursor.get("scope")))c.fail("SCOPE_MISMATCH","Cursor belongs to another Namespace");
            if(!c.actorId.equals(cursor.get("actor")))c.fail("VALIDATION_FAILED","Cursor belongs to another actor");
            if(((Number)cursor.get("revision")).longValue()!=c.revision)c.fail("REVISION_CONFLICT","Cursor revision changed");
            if(!hash.equals(cursor.get("hash")))c.fail("VALIDATION_FAILED","Cursor does not match query");
            offset=((Number)cursor.get("offset")).longValue();
            if(offset<0)c.fail("VALIDATION_FAILED","Invalid cursor offset");
        }
        prepare(request);
        try {
            var rows=new ArrayList<Map<String,Object>>();
            boolean more;
            try(var stream=execute(offset)) {
                var iterator=stream.iterator();
                while(rows.size()<size&&iterator.hasNext()) {
                    var row=iterator.next();
                    rows.add(Json.map("values",values(row,true),"presence",presence(row)));
                }
                more=iterator.hasNext();
            }
            var meta=c.meta();
            if(more)meta.put("nextCursor",CursorCodec.encode(c,Json.map("scope",c.namespaceId,"actor",c.actorId,"hash",hash,
                "revision",c.revision,"offset",offset+rows.size(),"expires",System.currentTimeMillis()+900000L)));
            var data=Json.map("columns",plan.columns(),"rows",rows);
            checkQueryRevision();
            return new DataRow(data,meta);
        } catch(RuntimeException failure) {
            if(expired.get())c.fail("LIMIT_EXCEEDED","Query timeout exceeded");
            throw failure;
        } finally {
            timer.cancel(false);
        }
    }
}
