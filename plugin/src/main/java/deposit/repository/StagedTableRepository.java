package deposit.repository;

import deposit.common.OperationalStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Metadata for file-backed tables and their durable source leases. */
public final class StagedTableRepository {
    private final OperationalStore store;

    public record Table(String id, String namespace, String actor, String resource, String path, String rowIndexPath,
                        long created, long expires, String columns, long rows, boolean valid, String diagnostics,
                        boolean diagnosticsTruncated) {}

    public StagedTableRepository(OperationalStore store) { this.store = store; }

    public static void initialize(Statement sql) throws SQLException {
        sql.execute("""
            CREATE TABLE staged_tables (
                id TEXT PRIMARY KEY, namespace_id TEXT NOT NULL, creator_id TEXT NOT NULL, resource_id TEXT NOT NULL,
                path TEXT NOT NULL, row_index_path TEXT NOT NULL, created_at INTEGER NOT NULL,
                last_access_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, columns_json TEXT NOT NULL,
                row_count INTEGER NOT NULL, valid INTEGER NOT NULL, diagnostics_json TEXT NOT NULL,
                diagnostics_truncated INTEGER NOT NULL, unit_id TEXT
            ) STRICT
            """);
        sql.execute("CREATE INDEX staged_table_expiry ON staged_tables(expires_at) WHERE unit_id IS NULL");
        sql.execute("CREATE INDEX staged_table_pending ON staged_tables(unit_id) WHERE unit_id IS NOT NULL");
        sql.execute("""
            CREATE TABLE resource_leases (
                id TEXT PRIMARY KEY, resource_id TEXT NOT NULL, namespace_id TEXT NOT NULL,
                table_id TEXT NOT NULL REFERENCES staged_tables(id) ON DELETE CASCADE,
                expires_at INTEGER NOT NULL
            ) STRICT
            """);
        sql.execute("CREATE INDEX resource_lease_resource ON resource_leases(resource_id,expires_at)");
        sql.execute("CREATE INDEX resource_lease_table ON resource_leases(table_id)");
    }

    public void create(Table table, String lease, String unit) {
        long payload = 3L * (table.columns().length() + table.diagnostics().length());
        store.write(payload, db -> {
            try (var insert = db.prepareStatement("INSERT INTO staged_tables VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, table.id());
                insert.setString(2, table.namespace());
                insert.setString(3, table.actor());
                insert.setString(4, table.resource());
                insert.setString(5, table.path());
                insert.setString(6, table.rowIndexPath());
                insert.setLong(7, table.created());
                insert.setLong(8, table.created());
                insert.setLong(9, table.expires());
                insert.setString(10, table.columns());
                insert.setLong(11, table.rows());
                insert.setBoolean(12, table.valid());
                insert.setString(13, table.diagnostics());
                insert.setBoolean(14, table.diagnosticsTruncated());
                insert.setString(15, unit);
                insert.executeUpdate();
            }
            try (var insert = db.prepareStatement("INSERT INTO resource_leases VALUES (?,?,?,?,?)")) {
                insert.setString(1, lease);
                insert.setString(2, table.resource());
                insert.setString(3, table.namespace());
                insert.setString(4, table.id());
                insert.setLong(5, Long.MAX_VALUE);
                insert.executeUpdate();
            }
            return null;
        });
    }

    public Table table(String id) {
        return store.read(db -> {
            try (var query = db.prepareStatement("SELECT * FROM staged_tables WHERE id=? AND unit_id IS NULL")) {
                query.setString(1, id);
                try (var row = query.executeQuery()) {
                    return row.next() ? new Table(row.getString("id"), row.getString("namespace_id"),
                        row.getString("creator_id"), row.getString("resource_id"), row.getString("path"),
                        row.getString("row_index_path"), row.getLong("created_at"), row.getLong("expires_at"),
                        row.getString("columns_json"), row.getLong("row_count"), row.getBoolean("valid"),
                        row.getString("diagnostics_json"), row.getBoolean("diagnostics_truncated")) : null;
                }
            }
        });
    }

    public boolean protects(String resource, long now) {
        return store.read(db -> {
            try (var query = db.prepareStatement("SELECT 1 FROM resource_leases WHERE resource_id=? AND expires_at>? LIMIT 1")) {
                query.setString(1, resource);
                query.setLong(2, now);
                try (var rows = query.executeQuery()) { return rows.next(); }
            }
        });
    }

    public void touch(String id, long now) {
        store.write(0, db -> {
            try (var update = db.prepareStatement("UPDATE staged_tables SET last_access_at=max(last_access_at,?) WHERE id=?")) {
                update.setLong(1, now);
                update.setString(2, id);
                update.executeUpdate();
            }
            return null;
        });
    }

    public List<String> expired(long now) {
        return store.read(db -> {
            var ids = new ArrayList<String>();
            try (var query = db.prepareStatement("""
                    SELECT id FROM staged_tables WHERE expires_at<=? AND unit_id IS NULL ORDER BY expires_at LIMIT 128
                    """)) {
                query.setLong(1, now);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) ids.add(rows.getString(1));
                }
            }
            return ids;
        });
    }

    public List<String> stagingPaths() {
        return store.read(db -> {
            var paths = new ArrayList<String>();
            try (var query = db.prepareStatement("SELECT path,row_index_path FROM staged_tables");
                 var rows = query.executeQuery()) {
                while (rows.next()) {
                    paths.add(rows.getString(1));
                    paths.add(rows.getString(2));
                }
            }
            return paths;
        });
    }

    public void delete(String id) {
        store.write(0, db -> {
            try (var delete = db.prepareStatement("DELETE FROM staged_tables WHERE id=? AND unit_id IS NULL")) {
                delete.setString(1, id);
                delete.executeUpdate();
            }
            return null;
        });
    }

    static void settle(Connection db, String unit, boolean committed) throws SQLException {
        String sql = committed ? "UPDATE staged_tables SET unit_id=NULL WHERE unit_id=?"
            : "DELETE FROM staged_tables WHERE unit_id=?";
        try (var statement = db.prepareStatement(sql)) {
            statement.setString(1, unit);
            statement.executeUpdate();
        }
    }
}
