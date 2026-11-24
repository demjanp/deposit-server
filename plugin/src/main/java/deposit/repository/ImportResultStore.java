package deposit.repository;

import deposit.common.OperationalStore;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Result metadata and independently paged, immutable result segments. */
public final class ImportResultStore {
    private final OperationalStore store;

    public record Root(String id, String namespace, String scope, String actor, String operation,
                       String kind, String state, long revision, String plan, long expires,
                       String sourcePackage, String collections) {}
    public record Collection(String id, String kind, long count) {}
    public record Entry(long position, String value, String key, String mapKey, String keyHash, long keyLength) {}
    public record Segment(String result, String collection, Entry entry) {}

    public ImportResultStore(OperationalStore store) { this.store = store; }

    public static void initialize(Statement sql) throws SQLException {
        sql.execute("""
            CREATE TABLE import_results (
                id TEXT PRIMARY KEY, namespace_id TEXT NOT NULL, scope TEXT NOT NULL, actor_id TEXT NOT NULL,
                operation_id TEXT NOT NULL, kind TEXT NOT NULL, state TEXT NOT NULL, revision INTEGER NOT NULL,
                plan_id TEXT, expires_at INTEGER NOT NULL, source_package_id TEXT, collections_json TEXT NOT NULL,
                unit_id TEXT, abandoned INTEGER NOT NULL DEFAULT 0
            ) STRICT
            """);
        sql.execute("CREATE INDEX import_result_expiry ON import_results(expires_at) WHERE unit_id IS NULL");
        sql.execute("CREATE INDEX import_result_pending ON import_results(unit_id) WHERE unit_id IS NOT NULL");
        sql.execute("""
            CREATE TABLE import_result_collections (
                result_id TEXT NOT NULL REFERENCES import_results(id) ON DELETE CASCADE,
                id TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, count INTEGER NOT NULL,
                PRIMARY KEY (result_id,id), UNIQUE (result_id,name)
            ) STRICT
            """);
        sql.execute("""
            CREATE TABLE import_result_entries (
                result_id TEXT NOT NULL, collection_id TEXT NOT NULL, position INTEGER NOT NULL,
                value_json TEXT NOT NULL, key_json TEXT, map_key TEXT, map_key_hash TEXT, map_key_length INTEGER NOT NULL,
                PRIMARY KEY (result_id,collection_id,position),
                FOREIGN KEY (result_id,collection_id) REFERENCES import_result_collections(result_id,id) ON DELETE CASCADE
            ) STRICT
            """);
        sql.execute("CREATE INDEX import_result_key ON import_result_entries(result_id,collection_id,map_key_hash,position)");
    }

    public void create(Root root, String unit) {
        store.write(0, db -> {
            try (var insert = db.prepareStatement("""
                    INSERT INTO import_results(id,namespace_id,scope,actor_id,operation_id,kind,state,revision,
                        plan_id,expires_at,source_package_id,collections_json,unit_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                insert.setString(1, root.id());
                insert.setString(2, root.namespace());
                insert.setString(3, root.scope());
                insert.setString(4, root.actor());
                insert.setString(5, root.operation());
                insert.setString(6, root.kind());
                insert.setString(7, root.state());
                insert.setLong(8, root.revision());
                insert.setString(9, root.plan());
                insert.setLong(10, root.expires());
                insert.setString(11, root.sourcePackage());
                insert.setString(12, root.collections());
                insert.setString(13, unit);
                insert.executeUpdate();
            }
            return null;
        });
    }

    public void complete(String id, String unit, long expires, String collections) {
        store.write(bytes(collections), db -> {
            try (var update = db.prepareStatement("""
                    UPDATE import_results SET expires_at=?,collections_json=COALESCE(?,collections_json)
                    WHERE id=? AND unit_id=?
                    """)) {
                update.setLong(1, expires);
                update.setString(2, collections);
                update.setString(3, id);
                update.setString(4, unit);
                if (update.executeUpdate() != 1) throw new SQLException("Missing pending import result");
            }
            return null;
        });
    }

    public Root root(String id, String unit) {
        return root(id, unit, false);
    }

    public Root collectable(String id) {
        return root(id, null, true);
    }

    private Root root(String id, String unit, boolean includeAbandoned) {
        return store.read(db -> {
            try (var query = db.prepareStatement("""
                    SELECT * FROM import_results WHERE id=? AND (unit_id IS NULL OR unit_id=?)
                        AND (abandoned=0 OR ?=1)
                    """)) {
                query.setString(1, id);
                query.setString(2, unit);
                query.setBoolean(3, includeAbandoned);
                try (var rows = query.executeQuery()) {
                    return rows.next() ? new Root(rows.getString("id"), rows.getString("namespace_id"),
                        rows.getString("scope"), rows.getString("actor_id"), rows.getString("operation_id"),
                        rows.getString("kind"), rows.getString("state"), rows.getLong("revision"),
                        rows.getString("plan_id"), rows.getLong("expires_at"), rows.getString("source_package_id"),
                        rows.getString("collections_json")) : null;
                }
            }
        });
    }

    public void createCollection(String result, String id, String name, String kind) {
        store.write(bytes(name), db -> {
            try (var insert = db.prepareStatement("INSERT INTO import_result_collections VALUES (?,?,?,?,0)")) {
                insert.setString(1, result);
                insert.setString(2, id);
                insert.setString(3, name);
                insert.setString(4, kind);
                insert.executeUpdate();
            }
            return null;
        });
    }

    public void completeCollection(String result, String id, long count) {
        store.write(0, db -> {
            try (var update = db.prepareStatement("UPDATE import_result_collections SET count=? WHERE result_id=? AND id=?")) {
                update.setLong(1, count);
                update.setString(2, result);
                update.setString(3, id);
                update.executeUpdate();
            }
            return null;
        });
    }

    public Collection collection(String result, String name) {
        return store.read(db -> {
            try (var query = db.prepareStatement("""
                    SELECT id,kind,count FROM import_result_collections WHERE result_id=? AND name=?
                    """)) {
                query.setString(1, result);
                query.setString(2, name);
                try (var rows = query.executeQuery()) {
                    return rows.next() ? new Collection(rows.getString(1), rows.getString(2), rows.getLong(3)) : null;
                }
            }
        });
    }

    public void append(List<Segment> segments) {
        long payload = 0;
        for (var segment : segments) {
            var entry = segment.entry();
            payload += bytes(entry.value()) + bytes(entry.key()) + bytes(entry.mapKey());
        }
        store.write(payload, db -> {
            try (var insert = db.prepareStatement("INSERT INTO import_result_entries VALUES (?,?,?,?,?,?,?,?)")) {
                for (var segment : segments) {
                    var entry = segment.entry();
                    insert.setString(1, segment.result());
                    insert.setString(2, segment.collection());
                    insert.setLong(3, entry.position());
                    insert.setString(4, entry.value());
                    insert.setString(5, entry.key());
                    insert.setString(6, entry.mapKey());
                    insert.setString(7, entry.keyHash());
                    insert.setLong(8, entry.keyLength());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return null;
        });
    }

    public List<Entry> entries(String result, String collection, long position, int limit) {
        return store.read(db -> {
            try (var query = db.prepareStatement("""
                    SELECT * FROM import_result_entries WHERE result_id=? AND collection_id=? AND position>=?
                    ORDER BY position LIMIT ?
                    """)) {
                query.setString(1, result);
                query.setString(2, collection);
                query.setLong(3, position);
                query.setInt(4, limit);
                return readEntries(query);
            }
        });
    }

    public List<Entry> matchingKeys(String result, String collection, String keyHash) {
        return store.read(db -> {
            try (var query = db.prepareStatement("""
                    SELECT * FROM import_result_entries WHERE result_id=? AND collection_id=? AND map_key_hash=?
                    ORDER BY position
                    """)) {
                query.setString(1, result);
                query.setString(2, collection);
                query.setString(3, keyHash);
                return readEntries(query);
            }
        });
    }

    private static List<Entry> readEntries(PreparedStatement query) throws SQLException {
        var entries = new ArrayList<Entry>();
        try (var rows = query.executeQuery()) {
            while (rows.next()) entries.add(readEntry(rows));
        }
        return entries;
    }

    private static Entry readEntry(ResultSet row) throws SQLException {
        return new Entry(row.getLong("position"), row.getString("value_json"), row.getString("key_json"),
            row.getString("map_key"), row.getString("map_key_hash"), row.getLong("map_key_length"));
    }

    public String expired(long cutoff) {
        return store.read(db -> {
            try (var query = db.prepareStatement("""
                    SELECT id FROM import_results WHERE expires_at<=? AND unit_id IS NULL ORDER BY expires_at LIMIT 1
                    """)) {
                query.setLong(1, cutoff);
                try (var rows = query.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
            }
        });
    }

    public boolean deleteSegments(String id) {
        return store.write(0, db -> {
            try (var entries = db.prepareStatement("""
                    DELETE FROM import_result_entries WHERE rowid IN (
                        SELECT rowid FROM import_result_entries WHERE result_id=? LIMIT 256
                    )
                    """);
                 var collections = db.prepareStatement("""
                    DELETE FROM import_result_collections WHERE rowid IN (
                        SELECT rowid FROM import_result_collections WHERE result_id=? LIMIT 256
                    )
                    """)) {
                entries.setString(1, id);
                collections.setString(1, id);
                return entries.executeUpdate() < 256 && collections.executeUpdate() < 256;
            }
        });
    }

    public void deleteRoot(Root result) {
        store.write(0, db -> {
            try (var receipt = db.prepareStatement("""
                    UPDATE operation_receipts SET result_collected=1
                    WHERE actor_id=? AND scope=? AND operation_id=? AND import_result_id=?
                      AND result_namespace_id=? AND result_kind=? AND unit_id IS NULL
                    """);
                 var delete = db.prepareStatement("DELETE FROM import_results WHERE id=? AND unit_id IS NULL")) {
                receipt.setString(1, result.actor());
                receipt.setString(2, "server".equals(result.scope()) ? "server" : result.namespace());
                receipt.setString(3, result.operation());
                receipt.setString(4, result.id());
                receipt.setString(5, result.namespace());
                receipt.setString(6, result.kind());
                receipt.executeUpdate();
                delete.setString(1, result.id());
                delete.executeUpdate();
            }
            return null;
        });
    }

    static void settle(Connection db, String unit, boolean committed) throws SQLException {
        String sql = committed ? "UPDATE import_results SET unit_id=NULL WHERE unit_id=?"
            : "UPDATE import_results SET unit_id=NULL,abandoned=1,expires_at=0 WHERE unit_id=?";
        try (var statement = db.prepareStatement(sql)) {
            statement.setString(1, unit);
            statement.executeUpdate();
        }
    }

    private static long bytes(String value) {
        return value == null ? 0L : value.getBytes(StandardCharsets.UTF_8).length;
    }
}
