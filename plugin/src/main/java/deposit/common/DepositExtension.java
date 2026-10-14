package deposit.common;

import org.neo4j.kernel.extension.*;
import org.neo4j.kernel.extension.context.ExtensionContext;
import org.neo4j.kernel.lifecycle.*;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.monitoring.Monitors;

/** Pinned Neo4j adapter: callbacks run after commit/rollback, including read-only transactions. */
public final class DepositExtension extends ExtensionFactory<DepositExtension.Dependencies> {
    public interface Dependencies {NamedDatabaseId databaseId();Monitors monitors();org.neo4j.kernel.internal.GraphDatabaseAPI database();org.neo4j.dbms.api.DatabaseManagementService management();}
    public DepositExtension() {super(ExtensionType.DATABASE,"deposit-core");}
    @Override public Lifecycle newInstance(ExtensionContext context,Dependencies dependencies) {
        return new LifecycleAdapter() {
            private ServerContext server;
            private Thread initializer;
            @Override public void start() {
                if(dependencies.databaseId().isSystemDatabase()) return;
                server=ServerContext.attach(dependencies.databaseId().databaseId().uuid());
                dependencies.monitors().addMonitorListener(server);
                dependencies.management().registerTransactionEventListener(dependencies.databaseId().name(),server);
                initializer=Thread.ofVirtual().name("deposit-schema-initialization").start(()->{
                    try {
                        if(!dependencies.database().isAvailable(60_000))throw new IllegalStateException("Database unavailable for schema initialization");
                        org.deposit.runtime.RuntimeReadiness.requireReady();
                        server.initialize(dependencies.database());
                    } catch(Throwable failure) {server.initializationFailed(failure);}
                });
            }
            @Override public void stop() {if(server!=null)server.beginStop();}
            @Override public void shutdown() {
                if(server!=null)server.beginStop();
                awaitInitializer(initializer,60_000);
                if(server!=null)server.shutdown();
                if(server!=null) {dependencies.management().unregisterTransactionEventListener(dependencies.databaseId().name(),server);dependencies.monitors().removeMonitorListener(server);ServerContext.detach(dependencies.databaseId().databaseId().uuid());}
            }
        };
    }
    static void awaitInitializer(Thread initializer,long timeoutMillis){
        if(initializer==null)return;
        initializer.interrupt();
        try{initializer.join(timeoutMillis);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted while joining Deposit initialization",e);}
        if(initializer.isAlive())throw new IllegalStateException("Deposit initialization did not stop; storage remains open");
    }
}
