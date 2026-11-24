package deposit.common;

import deposit.repository.*;
import java.util.*;
import java.time.*;
import java.io.*;
import java.nio.file.Path;
import org.neo4j.graphdb.*;

/** Durable, resumable transient cleanup. Runs before resource recovery and readiness. */
public final class HistoryLifecycle {
    private HistoryLifecycle() {}
    public interface Observer {void point(ServerContext server,String point,String namespace);}
    public static void recover(ServerContext server){
        if(server.storage!=null)try{HistoryWorkspace.recover(server.storage.files.path("staging/imports"));}catch(IOException failure){throw new IllegalStateException("Cannot reclaim private history indexes",failure);}
        var db=server.database();var observers=ServiceLoader.load(Observer.class,Observer.class.getClassLoader()).stream().map(ServiceLoader.Provider::get).toList();
        List<String> namespaces;try(var tx=db.beginTx()){namespaces=core(tx).nodes("Namespace",Map.of()).stream().map(CoreRepository::id).toList();}
        for(String ns:namespaces){
            java.util.function.Consumer<String> point=p->{for(var observer:observers)observer.point(server,p,ns);};
            try(var tx=db.beginTx()){
                var core=core(tx);core.lockNamespace(ns);Node root=core.namespace(ns);boolean invalidated=false;
                for(Node epoch:core.nodes("DepositHistoryEpoch",Map.of("namespaceId",ns)))if("transient".equals(epoch.getProperty("mode"))&&!server.runId.equals(epoch.getProperty("bootId"))){epoch.setProperty("expired",true);invalidated=true;}
                if(invalidated){root.setProperty("historyAvailability",((Number)root.getProperty("historyAvailability",0L)).longValue()+1);root.setProperty("historyCleanupPending",true);if("transient".equals(root.getProperty("historyMode")))root.removeProperty("headRevisionId");}
                tx.commit();
            }
            point.accept("history-invalidated");
            boolean remaining=true;
            while(remaining){
                remaining=false;int removed=0;
                try(var tx=db.beginTx()){
                    var core=core(tx);core.lockNamespace(ns);
                    Node epoch=null;try(var rows=tx.execute("MATCH (e:DepositHistoryEpoch {namespaceId:$ns,expired:true}) WHERE e.startedSequence IS NOT NULL RETURN e ORDER BY e.startedSequence LIMIT 1",Map.of("ns",ns))){if(rows.hasNext())epoch=(Node)rows.next().get("e");}
                    if(epoch!=null){String epochId=CoreRepository.id(epoch);
                        for(String label:List.of("DepositSnapshot","RevisionDependency","Revision")){
                            if(removed>=128)break;
                            String query="MATCH (n:"+label+" {namespaceId:$ns,historyEpochId:$epoch}) RETURN n LIMIT "+(128-removed);
                            List<Node> batch=new ArrayList<>();try(var rows=tx.execute(query,Map.of("ns",ns,"epoch",epochId))){while(rows.hasNext())batch.add((Node)rows.next().get("n"));}
                            for(Node record:batch){HistoryRepository.delete(record);removed++;}
                        }
                        if(removed==0)HistoryRepository.delete(epoch);
                        remaining=true;
                    }
                    tx.commit();
                }
                if(remaining)point.accept("history-cleanup-batch");
            }
            point.accept("history-before-baseline");
            try(var tx=db.beginTx()){
                var core=core(tx);core.lockNamespace(ns);Node root=core.namespace(ns);String mode=(String)root.getProperty("historyMode");
                if(mode.equals("transient")||mode.equals("persistent")&&!root.hasProperty("headRevisionId")){
                    if(server.storage==null)throw new IllegalStateException("History baseline requires initialized storage admission");
                    try(var budget=new BaselineBudget(server,ns);var workspace=new HistoryWorkspace(server.storage.files.path("staging/imports"),()->{((org.neo4j.kernel.impl.coreapi.InternalTransaction)tx).kernelTransaction().assertOpen();if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("History baseline interrupted");},budget::scratch,budget::scratchCleaned,budget::owns)){
                        new HistoryRepository(tx,core).baseline(ns,(String)root.getProperty("createdBy"),core.sequence(ns),server.runId,ZonedDateTime.now(ZoneOffset.UTC),workspace,budget::record);
                        root.removeProperty("historyCleanupPending");tx.commit();
                    }catch(IOException failure){throw new IllegalStateException("Cannot create admitted history baseline",failure);}
                }else{
                    root.removeProperty("historyCleanupPending");tx.commit();
                }
            }
            point.accept("history-after-baseline");
        }
    }
    private static CoreRepository core(Transaction tx){return new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC));}
    /** No request is admitted during startup; this lease still accounts scratch and database/log writes. */
    private static final class BaselineBudget implements AutoCloseable {
        final ServerContext server;final StorageAdmission.Reservation reservation;final Path scratch,database,logs;
        final Map<Path,Long> estimated=new HashMap<>(),reserved=new HashMap<>();
        boolean scratchCleaned;
        BaselineBudget(ServerContext server,String namespace)throws IOException{
            this.server=server;scratch=server.storage.files.root;database=server.database().databaseLayout().databaseDirectory();logs=server.database().databaseLayout().getTransactionLogsDirectory();
            reservation=server.storage.admission.acquire("history-baseline",namespace,UUID.randomUUID().toString(),"history-baseline",Map.of(scratch,0L),Json.map("phase","history-baseline","namespaceId",namespace,"scratchRoot","staging/imports"));
        }
        void add(Path path,long bytes){
            long next=Math.addExact(estimated.getOrDefault(path,0L),bytes),held=reserved.getOrDefault(path,0L);if(next>held){long addition=Math.max(1024L*1024,next-held);try{server.storage.admission.grow(reservation,Map.of(path,addition));}catch(IOException failure){throw new UncheckedIOException(failure);}reserved.put(path,Math.addExact(held,addition));}estimated.put(path,next);
        }
        void scratch(long bytes){add(scratch,bytes);}
        void owns(Path directory){var intent=new LinkedHashMap<>(reservation.writeIntent());intent.put("scratchPath",server.storage.files.root.relativize(directory).toString().replace('\\','/'));try{server.storage.admission.setWriteIntent(reservation,intent);}catch(IOException failure){throw new UncheckedIOException(failure);}}
        void scratchCleaned(){scratchCleaned=true;}
        void record(Map<String,Object> properties,int relationships){long[] size=ImportWriteBudget.historyRecordEstimate(properties,relationships);add(database,size[0]);add(logs,size[1]);}
        public void close()throws IOException{if(!scratchCleaned){server.storage.diagnostic="History baseline scratch cleanup pending";throw new IOException("History baseline scratch remains owned");}server.storage.admission.close(reservation);}
    }
}
