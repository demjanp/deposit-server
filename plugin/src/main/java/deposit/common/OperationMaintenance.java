package deposit.common;

import deposit.repository.OperationAttemptRepository;
import java.util.*;
import org.neo4j.graphdb.Node;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/** Settles attempts only after no worker can commit, and collects expired fences. */
final class OperationMaintenance {
    private static final int BATCH = 128;
    private final ServerContext server;
    private final GraphDatabaseAPI database;
    private String after = "";

    OperationMaintenance(ServerContext server, GraphDatabaseAPI database) {
        this.server = server;
        this.database = database;
    }

    void recover() {
        while (true) {
            var ids = new ArrayList<String>(BATCH);
            try (var tx = database.beginTx(); var rows = tx.execute("""
                    MATCH (n:DepositOperationAttempt)
                    WHERE n.state IN ['REGISTERED','RUNNING','CANCEL_REQUESTED']
                      AND n.incarnation <> $incarnation
                    RETURN elementId(n) AS id LIMIT $batch
                    """, Map.of("batch", BATCH,"incarnation",server.runId))) {
                while (rows.hasNext()) ids.add((String)rows.next().get("id"));
            }
            if (ids.isEmpty()) return;
            for (String id : ids) settle(id);
        }
    }

    void tick() {
        var ids = new ArrayList<String>(BATCH);
        try (var tx = database.beginTx(); var rows = tx.execute("""
                MATCH (n:DepositOperationAttempt)
                WHERE n.state IN ['REGISTERED','RUNNING','CANCEL_REQUESTED'] AND elementId(n) > $after
                RETURN elementId(n) AS id ORDER BY id LIMIT $batch
                """, Map.of("after", after, "batch", BATCH))) {
            while (rows.hasNext()) ids.add((String)rows.next().get("id"));
        }
        after = ids.size() == BATCH ? ids.getLast() : "";
        for (String id : ids) settle(id);
        collect();
    }

    private void settle(String id) {
        try (var tx = database.beginTx()) {
            Node node = tx.getNodeByElementId(id);
            long now = server.sessionTime();
            String next = settledState(node.getAllProperties(), now);
            if (next == null) return;
            tx.acquireWriteLock(node);
            next = settledState(node.getAllProperties(), now);
            if (next == null) return;
            Object sequence = node.getProperty("transactionSequence",null);
            OperationAttemptRepository.transition(node, next, now);
            OperationAttemptRepository.clearClaim(node);
            if (node.hasProperty("uploadId") || server.storage!=null&&hasOutputIntent(node))
                node.setProperty("cleanupState", "pending");
            tx.commit();
            if (sequence instanceof Long value) server.forgetCancelled(value);
        } catch (org.neo4j.graphdb.NotFoundException ignored) { /* Already collected. */ }
    }

    void finishCleanup() {
        try(var tx=database.beginTx();var rows=tx.execute("""
                MATCH (n:DepositOperationAttempt)
                WHERE n.cleanupState='pending' AND n.uploadId IS NULL
                  AND n.state IN ['FAILED_ROLLED_BACK','CANCELLED_ROLLED_BACK']
                RETURN n LIMIT $batch
                """,Map.of("batch",BATCH))) {
            while(rows.hasNext()){
                Node node=(Node)rows.next().get("n");
                if(!hasOutputIntent(node))node.setProperty("cleanupState","complete");
            }
            tx.commit();
        }
    }

    private boolean hasOutputIntent(Node node) {
        return server.storage.admission.hasOutputIntent((String)node.getProperty("actorId"),
            (String)node.getProperty("scope"),(String)node.getProperty("operationId"));
    }

    private String settledState(Map<String,Object> attempt, long now) {
        String state = (String)attempt.get("state");
        if (!OperationAttemptRepository.open(state)) return null;
        boolean incarnation = server.runId.equals(attempt.get("incarnation"));
        boolean connected = incarnation && server.sessionLive((String)attempt.get("sessionHash"))
            && server.connectionLive((String)attempt.get("connectionId"));
        if (state.equals("REGISTERED"))
            return !connected || now >= (long)attempt.get("claimDeadline") ? "CANCELLED_ROLLED_BACK" : null;
        var worker = incarnation ? server.activeTransaction((long)attempt.get("transactionSequence")) : null;
        if (worker == null || worker.operationSettled) {
            var completion = server.storage.operationalCommits.completion((String)attempt.get("actorId"),
                (String)attempt.get("scope"), (String)attempt.get("operationId"));
            if (completion != null && !completion.settled()) return null;
            return state.equals("CANCEL_REQUESTED")
                || incarnation && server.settledCancelled((long)attempt.get("transactionSequence")) ? "CANCELLED_ROLLED_BACK" : "FAILED_ROLLED_BACK";
        }
        if (!connected) worker.signalOperationCancellation();
        return null;
    }

    private void collect() {
        long now = server.sessionTime();
        try (var tx = database.beginTx()) {
            server.lockOperationAdmission(((InternalTransaction)tx).kernelTransaction());
            var admission = server.storage.operationalState.admission();
            if (now < admission.observedTime()) return;
            var ids = new ArrayList<String>(BATCH);
            try (var rows = tx.execute("""
                    MATCH (n:DepositOperationAttempt)
                    WHERE n.claimDeadline <= $now AND n.updatedAt <= $retainedSince
                      AND n.state IN ['FAILED_ROLLED_BACK','CANCELLED_ROLLED_BACK','COMPLETED_NO_DOMAIN_WRITE']
                      AND NOT coalesce(n.cleanupState,'none') IN ['pending','failed']
                    RETURN elementId(n) AS id LIMIT $batch
                    """, Map.of("now", now, "retainedSince", now - OperationAttemptRepository.RETENTION, "batch", BATCH))) {
                while (rows.hasNext()) ids.add((String)rows.next().get("id"));
            }
            if (ids.isEmpty()) return;
            long floor = admission.rejectedThrough();
            for (String id : ids) {
                Node node = tx.getNodeByElementId(id);
                String operation = (String)node.getProperty("operationId");
                var completion = server.storage.operationalCommits.completion((String)node.getProperty("actorId"),
                    (String)node.getProperty("scope"), operation);
                if (completion != null && !completion.settled()) continue;
                floor = Math.max(floor, OperationAttemptRepository.idTime(operation));
                node.delete();
            }
            // Stage 4 moves the records being deleted into the same SQLite transaction as this fence.
            server.storage.operationalState.advanceAdmission(floor, now);
            tx.commit();
        }
    }
}
