package org.deposit.probe;

import deposit.common.ServerContext;
import java.util.*;
import java.util.stream.Stream;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.event.*;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.procedure.*;

/** Isolated probe distribution only. No test control exists in the normal plugin catalog. */
public class SecurityProbe {
    @Context public GraphDatabaseAPI db;
    public static class Value {public final long value;Value(long value){this.value=value;}}
    @Procedure(name="stage0.security.clock",mode=Mode.READ)
    public Stream<Value> clock(@Name("offsetMillis") long offset) throws Exception {
        var server=ServerContext.forDatabase(db.databaseId().databaseId().uuid());
        var field=ServerContext.class.getDeclaredField("sessionClock");field.setAccessible(true);
        field.set(server,(java.util.function.LongSupplier)()->System.currentTimeMillis()+offset);
        return Stream.of(new Value(offset));
    }
    @Procedure(name="stage0.security.failCommit",mode=Mode.READ)
    public Stream<Value> failCommit(@Name("recoveryKey") String operation) {
        UUID.fromString(operation);
        var management=db.getDependencyResolver().resolveDependency(org.neo4j.dbms.api.DatabaseManagementService.class);
        var listener=new TransactionEventListener<Void>() {
            private volatile boolean armed=true;
            public Void beforeCommit(TransactionData data,Transaction tx,GraphDatabaseService database) {
                String identity=operation;
                try(var preparations=tx.findNodes(Label.label("DepositPreparedAction"),"recoveryKey",operation)){
                    if(preparations.hasNext())identity=(String)preparations.next().getProperty("operationId");
                }
                if(armed)for(Node node:data.createdNodes())if(node.hasLabel(Label.label("DepositReceipt"))&&identity.equals(node.getProperty("operationId",null))) {
                    armed=false;throw new IllegalStateException("STAGE2_INJECTED_COMMIT");
                }
                return null;
            }
            public void afterCommit(TransactionData data,Void state,GraphDatabaseService database) {}
            public void afterRollback(TransactionData data,Void state,GraphDatabaseService database) {}
        };
        management.registerTransactionEventListener(db.databaseName(),listener);
        return Stream.of(new Value(1));
    }
}
