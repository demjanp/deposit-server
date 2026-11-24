package deposit.repository;

import deposit.common.OperationalStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Actor-bound grants for completed export resources. */
public final class ExportGrantRepository {
    private final OperationalStore store;

    public ExportGrantRepository(OperationalStore store) { this.store = store; }

    public static void initialize(Statement sql) throws SQLException {
        sql.execute("""
            CREATE TABLE export_grants (
                id TEXT PRIMARY KEY, resource_id TEXT NOT NULL, namespace_id TEXT NOT NULL, actor_id TEXT NOT NULL,
                history INTEGER NOT NULL, expires_at INTEGER NOT NULL, unit_id TEXT, expire_unit TEXT
            ) STRICT
            """);
        sql.execute("CREATE INDEX export_grant_resource ON export_grants(resource_id,namespace_id,actor_id,expires_at)");
        sql.execute("CREATE INDEX export_grant_namespace ON export_grants(namespace_id)");
        sql.execute("CREATE INDEX export_grant_expiry ON export_grants(expires_at) WHERE unit_id IS NULL AND expire_unit IS NULL");
        sql.execute("CREATE INDEX export_grant_pending ON export_grants(unit_id) WHERE unit_id IS NOT NULL");
        sql.execute("CREATE INDEX export_grant_pending_expiration ON export_grants(expire_unit) WHERE expire_unit IS NOT NULL");
    }

    public void create(String id, String resource, String namespace, String actor, boolean history,
                       long expiry, String unit) {
        store.write(0, db -> {
            try (var insert = db.prepareStatement("""
                    INSERT INTO export_grants(id,resource_id,namespace_id,actor_id,history,expires_at,unit_id)
                    VALUES (?,?,?,?,?,?,?)
                    """)) {
                insert.setString(1, id);
                insert.setString(2, resource);
                insert.setString(3, namespace);
                insert.setString(4, actor);
                insert.setBoolean(5, history);
                insert.setLong(6, expiry);
                insert.setString(7, unit);
                insert.executeUpdate();
            }
            return null;
        });
    }

    public boolean permits(String resource, String namespace, String actor, boolean owner, long now) {
        return store.read(db -> {
            try (var query = db.prepareStatement("""
                    SELECT 1 FROM export_grants WHERE resource_id=? AND namespace_id=? AND actor_id=?
                      AND expires_at>? AND (history=0 OR ?=1) AND unit_id IS NULL AND expire_unit IS NULL LIMIT 1
                    """)) {
                query.setString(1, resource);
                query.setString(2, namespace);
                query.setString(3, actor);
                query.setLong(4, now);
                query.setBoolean(5, owner);
                try (var rows = query.executeQuery()) { return rows.next(); }
            }
        });
    }

    public boolean protects(String resource, long now) {
        return store.read(db -> {
            try (var query = db.prepareStatement("""
                    SELECT 1 FROM export_grants WHERE resource_id=? AND (expires_at>? OR unit_id IS NOT NULL) LIMIT 1
                    """)) {
                query.setString(1, resource);
                query.setLong(2, now);
                try (var rows = query.executeQuery()) { return rows.next(); }
            }
        });
    }

    public void expireNamespace(String namespace, String unit) {
        store.write(0, db -> {
            try (var update = db.prepareStatement("UPDATE export_grants SET expire_unit=? WHERE namespace_id=?")) {
                update.setString(1, unit);
                update.setString(2, namespace);
                update.executeUpdate();
            }
            return null;
        });
    }

    public void prune(long now) {
        store.write(0, db -> {
            try (var delete = db.prepareStatement("""
                    DELETE FROM export_grants WHERE id IN (
                        SELECT id FROM export_grants WHERE expires_at<=? AND unit_id IS NULL AND expire_unit IS NULL LIMIT 128
                    )
                    """)) {
                delete.setLong(1, now);
                delete.executeUpdate();
            }
            return null;
        });
    }

    static void settle(Connection db, String unit, boolean committed) throws SQLException {
        String creation = committed ? "UPDATE export_grants SET unit_id=NULL WHERE unit_id=?"
            : "DELETE FROM export_grants WHERE unit_id=?";
        try (var statement = db.prepareStatement(creation)) {
            statement.setString(1, unit);
            statement.executeUpdate();
        }
        String expiry = committed ? "UPDATE export_grants SET expires_at=0,expire_unit=NULL WHERE expire_unit=?"
            : "UPDATE export_grants SET expire_unit=NULL WHERE expire_unit=?";
        try (var statement = db.prepareStatement(expiry)) {
            statement.setString(1, unit);
            statement.executeUpdate();
        }
    }
}
