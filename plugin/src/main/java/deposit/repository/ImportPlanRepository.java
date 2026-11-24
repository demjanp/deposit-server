package deposit.repository;

import deposit.common.OperationalStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** The source binding, precondition and retained staging of an analyzed import. */
public final class ImportPlanRepository {
    private final OperationalStore store;

    public record Plan(String id, String namespace, String actor, String sourcePackage, String sourceGrant,
                       String tableId, String stagingPath, String admissionId, long packageBytes,
                       String packageSha256, long targetRevision, long created,
                       long expires, String state, String rules, String resultId, boolean writerBound) {
        public Plan transition(String next, long expiry) {
            return new Plan(id,namespace,actor,sourcePackage,sourceGrant,tableId,stagingPath,admissionId,
                packageBytes,packageSha256,targetRevision,created,expiry,next,rules,resultId,writerBound);
        }
    }

    public ImportPlanRepository(OperationalStore store) { this.store = store; }

    public static void initialize(Statement sql) throws SQLException {
        sql.execute("""
            CREATE TABLE import_plans (
                id TEXT NOT NULL, namespace_id TEXT NOT NULL, actor_id TEXT NOT NULL, source_package_id TEXT NOT NULL,
                source_grant_id TEXT, table_id TEXT, staging_path TEXT, admission_id TEXT,
                package_bytes INTEGER NOT NULL, package_sha256 TEXT NOT NULL, target_revision INTEGER NOT NULL,
                created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
                state TEXT NOT NULL, rules_json TEXT NOT NULL, result_id TEXT NOT NULL, writer_bound INTEGER NOT NULL,
                unit_id TEXT NOT NULL, PRIMARY KEY (id,unit_id)
            ) STRICT
            """);
        sql.execute("CREATE INDEX import_plan_pending ON import_plans(unit_id) WHERE unit_id<>''");
        sql.execute("CREATE INDEX import_plan_expiry ON import_plans(expires_at) WHERE unit_id=''");
        sql.execute("CREATE INDEX import_plan_path ON import_plans(staging_path)");
        sql.execute("CREATE INDEX import_plan_table ON import_plans(table_id)");
        sql.execute("CREATE INDEX import_plan_package ON import_plans(source_package_id,state,expires_at) WHERE source_grant_id IS NOT NULL");
        sql.execute("CREATE INDEX import_plan_cleanup ON import_plans(id) WHERE unit_id='' AND state<>'ready' AND staging_path IS NOT NULL");
    }

    public Plan plan(String id) { return plan(id,""); }

    public Plan plan(String id, String unit) {
        return store.read(db -> {
            try (var query = db.prepareStatement("""
                    SELECT * FROM import_plans WHERE id=? AND unit_id IN ('',?) ORDER BY unit_id DESC LIMIT 1
                    """)) {
                query.setString(1, id);
                query.setString(2, unit);
                try (var row = query.executeQuery()) {
                    return row.next() ? new Plan(row.getString("id"),row.getString("namespace_id"),
                        row.getString("actor_id"),row.getString("source_package_id"),row.getString("source_grant_id"),
                        row.getString("table_id"),row.getString("staging_path"),row.getString("admission_id"),
                        row.getLong("package_bytes"),row.getString("package_sha256"),row.getLong("target_revision"),
                        row.getLong("created_at"),row.getLong("expires_at"),
                        row.getString("state"),row.getString("rules_json"),row.getString("result_id"),
                        row.getBoolean("writer_bound")) : null;
                }
            }
        });
    }

    public void save(Plan plan, String unit) {
        store.write(3L*plan.rules().length(), db -> {
            try (var insert = db.prepareStatement("INSERT OR REPLACE INTO import_plans VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, plan.id());
                insert.setString(2, plan.namespace());
                insert.setString(3, plan.actor());
                insert.setString(4, plan.sourcePackage());
                insert.setString(5, plan.sourceGrant());
                insert.setString(6, plan.tableId());
                insert.setString(7, plan.stagingPath());
                insert.setString(8, plan.admissionId());
                insert.setLong(9, plan.packageBytes());
                insert.setString(10, plan.packageSha256());
                insert.setLong(11, plan.targetRevision());
                insert.setLong(12, plan.created());
                insert.setLong(13, plan.expires());
                insert.setString(14, plan.state());
                insert.setString(15, plan.rules());
                insert.setString(16, plan.resultId());
                insert.setBoolean(17, plan.writerBound());
                insert.setString(18, unit);
                insert.executeUpdate();
            }
            return null;
        });
    }

    public boolean hasPath(String path) {
        return store.read(db -> {
            try (var query = db.prepareStatement("SELECT 1 FROM import_plans WHERE staging_path=? LIMIT 1")) {
                query.setString(1, path);
                try (var rows = query.executeQuery()) { return rows.next(); }
            }
        });
    }

    public boolean protectsPackage(String id, long now) {
        return store.read(db -> {
            try (var query = db.prepareStatement("""
                    SELECT 1 FROM import_plans WHERE source_package_id=? AND source_grant_id IS NOT NULL
                     AND state='ready' AND (unit_id<>'' OR expires_at>?) LIMIT 1
                    """)) {
                query.setString(1, id);
                query.setLong(2, now);
                try (var rows = query.executeQuery()) { return rows.next(); }
            }
        });
    }

    public List<String> stagingPaths() {
        return store.read(db -> {
            var paths = new ArrayList<String>();
            try (var query = db.prepareStatement("SELECT DISTINCT staging_path FROM import_plans WHERE staging_path IS NOT NULL");
                 var rows = query.executeQuery()) {
                while (rows.next()) paths.add(rows.getString(1));
            }
            return paths;
        });
    }

    public List<String> cleanupCandidates(long now) {
        return store.read(db -> {
            var ids = new ArrayList<String>();
            try (var query = db.prepareStatement("""
                    SELECT id FROM import_plans WHERE unit_id=''
                        AND (expires_at<=? OR (staging_path IS NOT NULL AND state<>'ready'))
                    ORDER BY expires_at,id LIMIT 128
                    """)) {
                query.setLong(1, now);
                try (var rows = query.executeQuery()) { while (rows.next()) ids.add(rows.getString(1)); }
            }
            return ids;
        });
    }

    public List<String> boundPlans(String after) {
        return store.read(db -> {
            var ids = new ArrayList<String>();
            try (var query = db.prepareStatement("""
                    SELECT id FROM import_plans WHERE unit_id='' AND state='ready' AND writer_bound=1 AND id>?
                    ORDER BY id LIMIT 128
                    """)) {
                query.setString(1, after);
                try (var rows = query.executeQuery()) { while (rows.next()) ids.add(rows.getString(1)); }
            }
            return ids;
        });
    }

    public void clearTable(String id) { change("UPDATE import_plans SET table_id=NULL WHERE table_id=?",id); }

    public void clearStaging(String id) {
        change("UPDATE import_plans SET staging_path=NULL,admission_id=NULL WHERE id=? AND unit_id='' AND state<>'ready'",id);
    }

    public void delete(String id) { change("DELETE FROM import_plans WHERE id=? AND unit_id=''",id); }

    private void change(String sql, String id) {
        store.write(0, db -> {
            try (var statement = db.prepareStatement(sql)) {
                statement.setString(1, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    static void settle(Connection db, String unit, boolean committed) throws SQLException {
        String sql = committed ? "UPDATE OR REPLACE import_plans SET unit_id='' WHERE unit_id=?"
            : "DELETE FROM import_plans WHERE unit_id=?";
        try (var statement = db.prepareStatement(sql)) {
            statement.setString(1, unit);
            statement.executeUpdate();
        }
    }
}
