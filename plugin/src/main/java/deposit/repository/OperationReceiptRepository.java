package deposit.repository;

import deposit.common.ImportResultContract;
import deposit.common.OperationalStore;
import deposit.common.RequestContext;
import deposit.common.Results.Outcome;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import deposit.repository.OperationAttemptRepository.Key;
import deposit.repository.OperationAttemptRepository.RetentionCursor;

/** Commit evidence and optional import result identity, published with the owning action. */
public final class OperationReceiptRepository {
    private final OperationalStore store;
    private static final String STANDALONE_EXPIRY = """
        r.settled_at<=? AND r.id_time<=? AND r.unit_id IS NULL
        AND NOT EXISTS (SELECT 1 FROM operation_attempts a
            WHERE a.actor_id=r.actor_id AND a.scope=r.scope AND a.operation_id=r.operation_id)
        AND NOT EXISTS (SELECT 1 FROM import_results result WHERE result.id=r.import_result_id AND result.abandoned=0)
        """;

    public record Receipt(String actor, String scope, String operation, String requestHash, long revision,
                          boolean administrativeMode, String deletedNamespaceId, String importResultId,
                          String resultKind, String resultNamespace, boolean resultCollected) {}
    public record Expiry(RetentionCursor cursor,long time) {}

    public OperationReceiptRepository(OperationalStore store) { this.store = store; }

    public static void initialize(Statement sql) throws SQLException {
        sql.execute("""
            CREATE TABLE operation_receipts (
                actor_id TEXT NOT NULL, scope TEXT NOT NULL, operation_id TEXT NOT NULL, request_hash TEXT NOT NULL,
                revision INTEGER NOT NULL, administrative_mode INTEGER NOT NULL, deleted_namespace_id TEXT,
                import_result_id TEXT, result_kind TEXT, result_namespace_id TEXT,
                result_collected INTEGER NOT NULL DEFAULT 0, unit_id TEXT, settled_at INTEGER, id_time INTEGER NOT NULL,
                PRIMARY KEY (actor_id,scope,operation_id)
            ) STRICT
            """);
        sql.execute("CREATE INDEX operation_receipt_scope ON operation_receipts(scope,operation_id)");
        sql.execute("CREATE INDEX operation_receipt_pending ON operation_receipts(unit_id) WHERE unit_id IS NOT NULL");
        sql.execute("CREATE INDEX operation_receipt_settlement ON operation_receipts(settled_at) WHERE unit_id IS NULL");
    }

    public Receipt find(String actor, String scope, String operation, String unit) {
        return store.read(db -> {
            try (var query = db.prepareStatement("""
                    SELECT * FROM operation_receipts WHERE scope=? AND operation_id=?
                      AND (unit_id IS NULL OR unit_id=?) AND (? IS NULL OR actor_id=?) LIMIT 1
                    """)) {
                query.setString(1, scope);
                query.setString(2, operation);
                query.setString(3, unit);
                query.setString(4, actor);
                query.setString(5, actor);
                try (var row = query.executeQuery()) {
                    return row.next() ? new Receipt(row.getString("actor_id"),scope,operation,row.getString("request_hash"),
                        row.getLong("revision"),row.getBoolean("administrative_mode"),row.getString("deleted_namespace_id"),
                        row.getString("import_result_id"),row.getString("result_kind"),row.getString("result_namespace_id"),
                        row.getBoolean("result_collected")) : null;
                }
            }
        });
    }

    public void save(RequestContext context, String scope, String hash, Outcome outcome, String deletedNamespace,
                     String unit) {
        boolean result = ImportResultContract.FORMAT.equals(outcome.data().get("format"));
        store.write(0, db -> {
            try (var insert = db.prepareStatement("""
                    INSERT INTO operation_receipts(actor_id,scope,operation_id,request_hash,revision,administrative_mode,
                        deleted_namespace_id,import_result_id,result_kind,result_namespace_id,unit_id,id_time)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                insert.setString(1, context.actorId);
                insert.setString(2, scope);
                insert.setString(3, context.operationId);
                insert.setString(4, hash);
                insert.setLong(5, context.revision);
                insert.setBoolean(6, context.state.scoped ? context.state.administrative
                    : context.session != null && context.session.administrative);
                insert.setString(7, deletedNamespace);
                insert.setString(8, result ? (String)outcome.data().get("resultId") : null);
                insert.setString(9, result ? (String)outcome.data().get("kind") : null);
                insert.setString(10, result ? (String)outcome.data().get("namespaceId") : null);
                insert.setString(11, unit);
                var operation=UUID.fromString(context.operationId);
                insert.setLong(12,operation.version()==7 && operation.variant()==2
                    ? operation.getMostSignificantBits() >>> 16 : -1);
                insert.executeUpdate();
            }
            return null;
        });
    }

    public List<Expiry> standaloneExpiry(long cutoff,RetentionCursor after) {
        return store.read(db -> {
            var expired=new ArrayList<Expiry>();
            try(var query=db.prepareStatement("""
                    SELECT actor_id,scope,operation_id,settled_at,id_time FROM operation_receipts r WHERE
                    """+STANDALONE_EXPIRY+"""
                    AND (settled_at,actor_id,scope,operation_id)>(?,?,?,?)
                    ORDER BY settled_at,actor_id,scope,operation_id LIMIT 128
                    """)) {
                query.setLong(1,cutoff);
                query.setLong(2,cutoff);
                OperationAttemptRepository.bindCursor(query,3,after);
                try(var rows=query.executeQuery()) {
                    while(rows.next())expired.add(new Expiry(new RetentionCursor(rows.getLong(4),
                        new Key(rows.getString(1),rows.getString(2),rows.getString(3))),rows.getLong(5)));
                }
            }
            return expired;
        });
    }

    static long collectStandalone(Connection db,List<Expiry> receipts,long cutoff,long fence)throws SQLException {
        try(var delete=db.prepareStatement("""
                DELETE FROM operation_receipts AS r WHERE actor_id=? AND scope=? AND operation_id=? AND
                """+STANDALONE_EXPIRY)) {
            for(var receipt:receipts) {
                OperationAttemptRepository.bindKey(delete,1,receipt.cursor().key());
                delete.setLong(4,cutoff);
                delete.setLong(5,cutoff);
                if(delete.executeUpdate()==1)fence=Math.max(fence,receipt.time());
            }
        }
        return fence;
    }

    static void settle(Connection db, String unit, boolean committed, long now) throws SQLException {
        String sql = committed ? "UPDATE operation_receipts SET unit_id=NULL,settled_at=? WHERE unit_id=?"
            : "DELETE FROM operation_receipts WHERE unit_id=?";
        try (var statement = db.prepareStatement(sql)) {
            if (committed) statement.setLong(1, now);
            statement.setString(committed ? 2 : 1, unit);
            statement.executeUpdate();
        }
    }
}
