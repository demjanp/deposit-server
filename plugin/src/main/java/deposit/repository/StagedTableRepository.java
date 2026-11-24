package deposit.repository;

import deposit.common.OperationalStore;
import deposit.common.RequestContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Metadata for file-backed tables, which protect their source until removed. */
public final class StagedTableRepository {
    private final OperationalStore store;

    public record Table(String id, String namespace, String actor, String resource,
                        long created, long expires, String columns, long rows, boolean valid) {
        public String path() { return path(id); }
        public String rowIndexPath() { return rowIndexPath(id); }

        public static String path(String id) { return base(id) + ".json"; }
        public static String rowIndexPath(String id) { return base(id) + ".idx"; }
        public static String sourcePath(String id) { return base(id) + ".source"; }

        private static String base(String id) { return "staging/tables/" + RequestContext.uuid(id); }
    }

    public StagedTableRepository(OperationalStore store) { this.store = store; }

    public static void initialize(Statement sql) throws SQLException {
        sql.execute("""
            CREATE TABLE staged_tables (
                id TEXT PRIMARY KEY, namespace_id TEXT NOT NULL, creator_id TEXT NOT NULL, resource_id TEXT NOT NULL,
                created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, columns_json TEXT NOT NULL,
                row_count INTEGER NOT NULL, valid INTEGER NOT NULL, unit_id TEXT
            ) STRICT
            """);
        sql.execute("CREATE INDEX staged_table_expiry ON staged_tables(expires_at) WHERE unit_id IS NULL");
        sql.execute("CREATE INDEX staged_table_pending ON staged_tables(unit_id) WHERE unit_id IS NOT NULL");
        sql.execute("CREATE INDEX staged_table_resource ON staged_tables(resource_id)");
    }

    public void create(Table table, String unit) {
        if (!table.id().equals(RequestContext.uuid(table.id()))) throw new IllegalArgumentException("Noncanonical table ID");
        long payload = 3L * table.columns().length();
        store.write(payload, db -> {
            try (var insert = db.prepareStatement("INSERT INTO staged_tables VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, table.id());
                insert.setString(2, table.namespace());
                insert.setString(3, table.actor());
                insert.setString(4, table.resource());
                insert.setLong(5, table.created());
                insert.setLong(6, table.expires());
                insert.setString(7, table.columns());
                insert.setLong(8, table.rows());
                insert.setBoolean(9, table.valid());
                insert.setString(10, unit);
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
                        row.getString("creator_id"), row.getString("resource_id"), row.getLong("created_at"),
                        row.getLong("expires_at"), row.getString("columns_json"), row.getLong("row_count"),
                        row.getBoolean("valid")) : null;
                }
            }
        });
    }

    public boolean protects(String resource) {
        return store.read(db -> {
            try (var query = db.prepareStatement("SELECT 1 FROM staged_tables WHERE resource_id=? LIMIT 1")) {
                query.setString(1, resource);
                try (var rows = query.executeQuery()) { return rows.next(); }
            }
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
            try (var query = db.prepareStatement("SELECT id FROM staged_tables");
                 var rows = query.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString(1);
                    paths.add(Table.path(id));
                    paths.add(Table.rowIndexPath(id));
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
