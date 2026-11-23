package deposit.repository;

import deposit.common.*;
import java.util.Map;
import org.neo4j.graphdb.*;

/** Persistent registration, cancellation fence and transaction-coupled completion. */
public final class OperationAttemptRepository {
    public static final long RETENTION = 86_400_000L;
    public static final long FUTURE_SKEW = 300_000L;
    private static final Label ATTEMPT = Label.label("DepositOperationAttempt");
    private static final Label ADMISSION = Label.label("DepositOperationAdmission");
    private final Transaction tx;

    public OperationAttemptRepository(Transaction tx) { this.tx = tx; }

    public Node find(String actor, String scope, String id) {
        try (var rows = tx.findNodes(ATTEMPT, Map.of("actorId", actor, "scope", scope, "operationId", id))) {
            return rows.hasNext() ? rows.next() : null;
        }
    }

    public Node create(String actor, String scope, String id) {
        Node node = tx.createNode(ATTEMPT);
        node.setProperty("actorId", actor);
        node.setProperty("scope", scope);
        node.setProperty("operationId", id);
        return node;
    }

    public static long idTime(String id) {
        var uuid = java.util.UUID.fromString(id);
        if (uuid.version() != 7 || uuid.variant() != 2 || !id.equals(uuid.toString()))
            throw ExceptionHandler.fail("VALIDATION_FAILED", "Expected a canonical UUIDv7 operation ID");
        return uuid.getMostSignificantBits() >>> 16;
    }

    public static String id(Object raw) {
        String id = RequestContext.uuid(raw);
        if (!id.equals(raw)) throw ExceptionHandler.fail("VALIDATION_FAILED", "Expected a canonical UUIDv7 operation ID");
        idTime(id);
        return id;
    }

    public Node lockAdmission() {
        Node node = tx.findNode(ADMISSION, "id", "operations");
        if (node == null) throw new IllegalStateException("Operation admission unavailable");
        tx.acquireWriteLock(node);
        return node;
    }

    public void admit(Node admission, String id, long now) {
        if (!admissible(admission,id,now))
            throw ExceptionHandler.fail("OPERATION_ID_EXPIRED", "Operation ID is outside its admission window");
        admission.setProperty("observedTime", now);
    }

    public boolean admissible(Node admission,String id,long now) {
        long time = idTime(id);
        long floor = ((Number)admission.getProperty("rejectedThrough", -1L)).longValue();
        long clock = ((Number)admission.getProperty("observedTime", -1L)).longValue();
        if (now < clock)
            throw ExceptionHandler.fail("OUTCOME_UNKNOWN", "Operation admission is suspended until the server clock settles");
        return time > Math.max(floor, now - RETENTION) && time <= now + FUTURE_SKEW;
    }

    public static String state(Node node) { return (String)node.getProperty("state"); }

    public static void transition(Node node, String state, long now) {
        node.setProperty("state", state);
        node.setProperty("updatedAt", now);
    }

    public static boolean open(String state) {
        return switch (state) {
            case "REGISTERED", "RUNNING", "CANCEL_REQUESTED" -> true;
            default -> false;
        };
    }

    public static void clearClaim(Node node) {
        for (String key : new String[]{"bindings", "sessionHash", "connectionId", "requestId", "transactionSequence"})
            node.removeProperty(key);
    }

    public static void initialize(Transaction tx, Long restoredAt) {
        Node node = tx.findNode(ADMISSION, "id", "operations");
        if (node == null) {
            node = tx.createNode(ADMISSION);
            node.setProperty("id", "operations");
        }
        if (restoredAt != null) {
            long floor = ((Number)node.getProperty("rejectedThrough", -1L)).longValue();
            node.setProperty("rejectedThrough", Math.max(floor, Math.addExact(restoredAt, FUTURE_SKEW)));
        }
    }
}
