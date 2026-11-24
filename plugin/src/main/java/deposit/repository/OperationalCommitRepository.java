package deposit.repository;

import deposit.common.ExceptionHandler;
import deposit.common.OperationalStore;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Typed operation intent, pending terminal result, and bounded witness ownership. */
public final class OperationalCommitRepository {
    public static final String MARKER = "_depositCommitUnit";
    private final OperationalStore store;

    public OperationalCommitRepository(OperationalStore store) { this.store = store; }

    public record Unit(String id, String actor, String scope, String operation, String inputHash,
                       String ownerKind, String ownerId) {}
    public record Pending(String id, String ownerKind, String ownerId, String previousMarker, boolean prepared) {}
    public record Completion(String state, String result, boolean settled) {}

    public void begin(Unit unit) {
        store.write(0, db -> {
            try (var insert = db.prepareStatement("""
                    INSERT INTO operation_attempts
                    (actor_id,scope,operation_id,input_hash,owner_kind,owner_id,unit_id)
                    VALUES (?,?,?,?,?,?,?)
                    """)) {
                insert.setString(1, unit.actor());
                insert.setString(2, unit.scope());
                insert.setString(3, unit.operation());
                insert.setString(4, unit.inputHash());
                insert.setString(5, unit.ownerKind());
                insert.setString(6, unit.ownerId());
                insert.setString(7, unit.id());
                insert.executeUpdate();
            }
            return null;
        });
    }

    public void prepare(Unit unit, String previousMarker, String state, String result) {
        store.write(result.getBytes(StandardCharsets.UTF_8).length, db -> {
            try (var update = db.prepareStatement("""
                    UPDATE operation_attempts SET previous_marker=?,state=?,result_json=?
                    WHERE unit_id=? AND state IS NULL AND settled_at IS NULL
                    """)) {
                update.setString(1, previousMarker);
                update.setString(2, state);
                update.setString(3, result);
                update.setString(4, unit.id());
                if (update.executeUpdate() != 1) throw new SQLException("Operation intent unavailable at commit");
            }
            return null;
        });
    }

    public void requireSettled(String kind, String owner, String currentUnit) {
        boolean pending = store.read(db -> {
            String sql = """
                SELECT 1 FROM operation_attempts
                WHERE owner_kind=? AND state IS NOT NULL AND settled_at IS NULL AND unit_id IS NOT ?
                """;
            if (owner != null) sql += " AND owner_id=?";
            try (var query = db.prepareStatement(sql)) {
                query.setString(1, kind);
                query.setString(2, currentUnit);
                if (owner != null) query.setString(3, owner);
                try (var row = query.executeQuery()) {
                    return row.next();
                }
            }
        });
        if (pending) throw ExceptionHandler.fail("OUTCOME_UNKNOWN", "An operation in this scope is awaiting settlement");
    }

    public Completion completion(String actor, String scope, String operation) {
        return store.read(db -> {
            try (var query = db.prepareStatement("""
                    SELECT state,result_json,settled_at IS NOT NULL FROM operation_attempts
                    WHERE actor_id=? AND scope=? AND operation_id=?
                    """)) {
                query.setString(1, actor);
                query.setString(2, scope);
                query.setString(3, operation);
                try (var row = query.executeQuery()) {
                    return !row.next() ? null : row.getBoolean(3)
                        ? new Completion(row.getString(1), row.getString(2), true) : new Completion(null, null, false);
                }
            }
        });
    }

    public List<Pending> pending() {
        return store.read(db -> {
            var pending = new ArrayList<Pending>();
            try (var query = db.prepareStatement("""
                    SELECT unit_id,owner_kind,owner_id,previous_marker,state IS NOT NULL
                    FROM operation_attempts WHERE settled_at IS NULL
                    """); var rows = query.executeQuery()) {
                while (rows.next()) pending.add(new Pending(rows.getString(1), rows.getString(2),
                    rows.getString(3), rows.getString(4), rows.getBoolean(5)));
            }
            return pending;
        });
    }

    public void settle(String unit, boolean committed, long now) {
        store.write(0, db -> {
            String sql = committed
                ? "UPDATE operation_attempts SET settled_at=?,previous_marker=NULL WHERE unit_id=? AND state IS NOT NULL AND settled_at IS NULL"
                : "DELETE FROM operation_attempts WHERE unit_id=? AND settled_at IS NULL";
            try (var update = db.prepareStatement(sql)) {
                if (committed) update.setLong(1, now);
                update.setString(committed ? 2 : 1, unit);
                if (update.executeUpdate() == 0 && committed) {
                    try (var query = db.prepareStatement("SELECT settled_at IS NOT NULL FROM operation_attempts WHERE unit_id=?")) {
                        query.setString(1, unit);
                        try (var row = query.executeQuery()) {
                            if (!row.next() || !row.getBoolean(1)) throw new SQLException("Missing prepared operation result");
                        }
                    }
                }
            }
            return null;
        });
    }
}
