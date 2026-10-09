package deposit.common;

import deposit.repository.*;
import java.util.*;
import java.time.*;
import org.neo4j.graphdb.*;

/** Durable, resumable transient cleanup. Runs before resource recovery and readiness. */
public final class HistoryLifecycle {
    private HistoryLifecycle() {}
    public interface Observer {void point(ServerContext server,String point,String namespace);}
    public static void recover(ServerContext server){
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
                    outer:for(Node epoch:core.nodes("DepositHistoryEpoch",Map.of("namespaceId",ns)))if(Boolean.TRUE.equals(epoch.getProperty("expired",false))){
                        for(String label:List.of("DepositSnapshot","RevisionTag","Revision"))for(Node record:core.nodes(label,Map.of("namespaceId",ns))){
                            boolean belongs=Objects.equals(record.getProperty("historyEpochId",null),CoreRepository.id(epoch));
                            if(label.equals("RevisionTag")){Node revision=core.entity("Revision",ns,(String)record.getProperty("revisionId"));belongs=revision==null||Objects.equals(revision.getProperty("historyEpochId"),CoreRepository.id(epoch));}
                            if(belongs){HistoryRepository.delete(record);if(++removed>=128){remaining=true;break outer;}}
                        }
                        HistoryRepository.delete(epoch);
                    }
                    tx.commit();
                }
                point.accept("history-cleanup-batch");
            }
            point.accept("history-before-baseline");
            try(var tx=db.beginTx()){
                var core=core(tx);core.lockNamespace(ns);Node root=core.namespace(ns);String mode=(String)root.getProperty("historyMode");
                if(mode.equals("transient")||mode.equals("persistent")&&!root.hasProperty("headRevisionId"))new HistoryRepository(tx,core).baseline(ns,(String)root.getProperty("createdBy"),core.sequence(ns),server.runId,ZonedDateTime.now(ZoneOffset.UTC));
                root.removeProperty("historyCleanupPending");tx.commit();
            }
            point.accept("history-after-baseline");
        }
    }
    private static CoreRepository core(Transaction tx){return new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC));}
}
