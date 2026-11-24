package deposit.repository;

import deposit.common.ExceptionHandler;
import deposit.common.OperationalStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Durable package metadata and verified upload progress. The ZIP and index stay on disk. */
public final class ExchangeRepository {
    private static final String STAGE_COLUMNS = """
        id,actor_id,scope,state,admission_id,input_kind,include_history,writer_bound,
        grant_id,grant_kind,history,bytes,sha256,expires_at,cleanup_after,remove_after,input_cleanup,cleaned,unit_id
        """;
    private static final String MEMBER_COLUMNS = "upload_id,name,bytes,digest_state,tail_sha256,unit_id";
    private final OperationalStore store;

    public ExchangeRepository(OperationalStore store) { this.store = store; }

    public record Stage(String id, String actorId, String scope, String state, String admissionId,
                        String inputKind, boolean includeHistory, boolean writerBound, String grant,
                        String grantKind, boolean history, long bytes, String sha256, long expiresAt,
                        long cleanupAfter, long removeAfter, boolean inputCleanup, boolean cleaned) {
        public static Stage open(String id, String actor, String scope, String state, String admission,
                                 String kind, boolean includeHistory, boolean bound, long expiry) {
            return new Stage(id, actor, scope, state, admission, kind, includeHistory, bound, null, null,
                false, 0, null, expiry, expiry, Long.MAX_VALUE, false, false);
        }

        public Stage ready(String grant, String kind, boolean history, long bytes, String hash,
                           long expires, long cleanup) {
            return new Stage(id, actorId, scope, "ready", admissionId, inputKind, includeHistory, writerBound,
                grant, kind, history, bytes, hash, expires, cleanup, cleanup, inputKind != null, false);
        }

        public Stage cleanup(String state, long cleanup, long remove, boolean input, boolean done) {
            return new Stage(id, actorId, scope, state, admissionId, inputKind, includeHistory, writerBound,
                grant, grantKind, history, bytes, sha256, expiresAt, cleanup, remove, input, done);
        }
    }

    public record Member(String name, long size, byte[] digest, String tailSha256) {}

    public static void initialize(Statement sql) throws SQLException {
        sql.execute("""
            CREATE TABLE exchange_stages (
                id TEXT NOT NULL, actor_id TEXT NOT NULL, scope TEXT NOT NULL, state TEXT NOT NULL,
                admission_id TEXT NOT NULL, input_kind TEXT, include_history INTEGER NOT NULL,
                writer_bound INTEGER NOT NULL, grant_id TEXT, grant_kind TEXT, history INTEGER NOT NULL,
                bytes INTEGER NOT NULL, sha256 TEXT, expires_at INTEGER NOT NULL, cleanup_after INTEGER NOT NULL,
                remove_after INTEGER NOT NULL, input_cleanup INTEGER NOT NULL, cleaned INTEGER NOT NULL,
                unit_id TEXT NOT NULL, PRIMARY KEY (id,unit_id)
            ) STRICT
            """);
        sql.execute("CREATE INDEX exchange_cleanup ON exchange_stages(cleanup_after) WHERE unit_id=''");
        sql.execute("CREATE INDEX exchange_removal ON exchange_stages(remove_after) WHERE unit_id=''");
        sql.execute("CREATE INDEX exchange_input_cleanup ON exchange_stages(id) WHERE unit_id='' AND input_cleanup=1");
        sql.execute("CREATE INDEX exchange_pending_stage ON exchange_stages(unit_id) WHERE unit_id<>''");
        sql.execute("""
            CREATE TABLE exchange_members (
                upload_id TEXT NOT NULL, name TEXT NOT NULL, bytes INTEGER NOT NULL,
                digest_state BLOB NOT NULL, tail_sha256 TEXT NOT NULL, unit_id TEXT NOT NULL,
                PRIMARY KEY (upload_id,name,unit_id)
            ) STRICT
            """);
        sql.execute("CREATE INDEX exchange_pending_member ON exchange_members(unit_id) WHERE unit_id<>''");
    }

    public Stage stage(String id) {
        return store.read(db -> {
            try (var query = db.prepareStatement("SELECT " + STAGE_COLUMNS + " FROM exchange_stages WHERE id=? AND unit_id=''")) {
                query.setString(1, id);
                try (var rows = query.executeQuery()) {
                    return rows.next() ? readStage(rows) : null;
                }
            }
        });
    }

    public void requireSettled(String id, String current) {
        boolean pending = store.read(db -> {
            try (var query = db.prepareStatement("""
                    SELECT 1 FROM exchange_stages WHERE id=? AND unit_id<>'' AND unit_id IS NOT ?
                    UNION ALL
                    SELECT 1 FROM exchange_members WHERE upload_id=? AND unit_id<>'' AND unit_id IS NOT ?
                    LIMIT 1
                    """)) {
                query.setString(1, id);
                query.setString(2, current);
                query.setString(3, id);
                query.setString(4, current);
                try (var rows = query.executeQuery()) { return rows.next(); }
            }
        });
        if (pending) throw ExceptionHandler.fail("OUTCOME_UNKNOWN", "Exchange package is awaiting settlement");
    }

    public boolean hasStage(String id) {
        return store.read(db -> {
            try (var query = db.prepareStatement("SELECT 1 FROM exchange_stages WHERE id=? LIMIT 1")) {
                query.setString(1, id);
                try (var rows = query.executeQuery()) { return rows.next(); }
            }
        });
    }

    public void save(Stage stage, String unit) {
        store.write(0, db -> {
            try (var write = db.prepareStatement("INSERT OR REPLACE INTO exchange_stages (" + STAGE_COLUMNS
                    + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                write.setString(1, stage.id());
                write.setString(2, stage.actorId());
                write.setString(3, stage.scope());
                write.setString(4, stage.state());
                write.setString(5, stage.admissionId());
                write.setString(6, stage.inputKind());
                write.setBoolean(7, stage.includeHistory());
                write.setBoolean(8, stage.writerBound());
                write.setString(9, stage.grant());
                write.setString(10, stage.grantKind());
                write.setBoolean(11, stage.history());
                write.setLong(12, stage.bytes());
                write.setString(13, stage.sha256());
                write.setLong(14, stage.expiresAt());
                write.setLong(15, stage.cleanupAfter());
                write.setLong(16, stage.removeAfter());
                write.setBoolean(17, stage.inputCleanup());
                write.setBoolean(18, stage.cleaned());
                write.setString(19, unit);
                write.executeUpdate();
            }
            return null;
        });
    }

    private static Stage readStage(ResultSet rows) throws SQLException {
        return new Stage(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
            rows.getString(5), rows.getString(6), rows.getBoolean(7), rows.getBoolean(8), rows.getString(9),
            rows.getString(10), rows.getBoolean(11), rows.getLong(12), rows.getString(13), rows.getLong(14),
            rows.getLong(15), rows.getLong(16), rows.getBoolean(17), rows.getBoolean(18));
    }

    public Member member(String id, String name) {
        return store.read(db -> {
            try (var query = db.prepareStatement("SELECT " + MEMBER_COLUMNS
                    + " FROM exchange_members WHERE upload_id=? AND name=? AND unit_id=''")) {
                query.setString(1, id);
                query.setString(2, name);
                try (var rows = query.executeQuery()) { return rows.next() ? readMember(rows) : null; }
            }
        });
    }

    public List<Member> resourceMembers(String id, String after) {
        return store.read(db -> {
            var members = new ArrayList<Member>();
            try (var query = db.prepareStatement("SELECT " + MEMBER_COLUMNS + " FROM exchange_members "
                    + "WHERE upload_id=? AND name>? AND name<'resources0' AND unit_id='' ORDER BY name LIMIT 128")) {
                query.setString(1, id);
                query.setString(2, after);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) members.add(readMember(rows));
                }
            }
            return members;
        });
    }

    private static Member readMember(ResultSet rows) throws SQLException {
        return new Member(rows.getString(2), rows.getLong(3), rows.getBytes(4), rows.getString(5));
    }

    public void save(String id, Member member, String unit) {
        store.write(0, db -> {
            try (var write = db.prepareStatement("INSERT OR REPLACE INTO exchange_members (" + MEMBER_COLUMNS
                    + ") VALUES (?,?,?,?,?,?)")) {
                write.setString(1, id);
                write.setString(2, member.name());
                write.setLong(3, member.size());
                write.setBytes(4, member.digest());
                write.setString(5, member.tailSha256());
                write.setString(6, unit);
                write.executeUpdate();
            }
            return null;
        });
    }

    public List<String> cleanupCandidates(long now) {
        return store.read(db -> {
            var ids = new ArrayList<String>();
            try (var query = db.prepareStatement("""
                    SELECT id FROM exchange_stages s WHERE unit_id='' AND
                    (input_cleanup=1 OR (cleaned=0 AND cleanup_after<=?) OR (cleaned=1 AND remove_after<=?))
                    AND (input_cleanup=1 OR NOT EXISTS (SELECT 1 FROM import_plans p WHERE p.source_package_id=s.id
                        AND p.source_grant_id IS NOT NULL AND p.state='ready' AND (p.unit_id<>'' OR p.expires_at>?)))
                    ORDER BY cleanup_after,id LIMIT 64
                    """)) {
                query.setLong(1, now);
                query.setLong(2, now);
                query.setLong(3, now);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) ids.add(rows.getString(1));
                }
            }
            return ids;
        });
    }

    public List<Stage> uploading(String after) {
        return store.read(db -> {
            var writers = new ArrayList<Stage>();
            try (var query = db.prepareStatement("SELECT " + STAGE_COLUMNS
                    + " FROM exchange_stages WHERE unit_id='' AND state='uploading' AND id>? ORDER BY id LIMIT 128")) {
                query.setString(1, after);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) writers.add(readStage(rows));
                }
            }
            return writers;
        });
    }

    public int deleteMembers(String id) {
        return store.write(0, db -> {
            try (var delete = db.prepareStatement("""
                    DELETE FROM exchange_members WHERE rowid IN (
                        SELECT rowid FROM exchange_members WHERE upload_id=? AND unit_id='' LIMIT 256
                    )
                    """)) {
                delete.setString(1, id);
                return delete.executeUpdate();
            }
        });
    }

    public void deleteStage(String id) {
        store.write(0, db -> {
            execute(db, "DELETE FROM exchange_stages WHERE id=? AND unit_id=''", id);
            return null;
        });
    }

    static void settle(Connection db, String unit, boolean committed) throws SQLException {
        if (committed) {
            execute(db, "UPDATE OR REPLACE exchange_stages SET unit_id='' WHERE unit_id=?", unit);
            execute(db, "UPDATE OR REPLACE exchange_members SET unit_id='' WHERE unit_id=?", unit);
        } else {
            execute(db, "DELETE FROM exchange_members WHERE unit_id=?", unit);
            execute(db, "DELETE FROM exchange_stages WHERE unit_id=?", unit);
        }
    }

    private static void execute(Connection db, String sql, String id) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }
}
