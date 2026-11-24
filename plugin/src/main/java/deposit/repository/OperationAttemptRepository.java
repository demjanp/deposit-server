package deposit.repository;

import deposit.common.ExceptionHandler;
import deposit.common.OperationalStore;
import deposit.common.RequestContext;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** Public attempt identity, state and result summary. Execution bindings live in ServerContext. */
public final class OperationAttemptRepository {
    public static final long RETENTION=86_400_000L;
    public static final long FUTURE_SKEW=300_000L;
    private final OperationalStore store;
    private static final String EXPIRABLE = """
        a.settled_at<=? AND a.claim_deadline<=? AND a.unit_id IS NULL AND a.cleanup_state NOT IN ('pending','failed')
        AND NOT EXISTS (SELECT 1 FROM import_results r
            WHERE r.id=json_extract(a.result_json,'$.resultId') AND r.abandoned=0)
        AND NOT EXISTS (SELECT 1 FROM resource_transfers t WHERE
            t.id IN (a.upload_id,json_extract(a.result_json,'$.uploadId'))
            OR t.resource_id=json_extract(a.result_json,'$.resourceId'))
        AND NOT EXISTS (SELECT 1 FROM exchange_stages e WHERE
            e.id IN (a.upload_id,json_extract(a.result_json,'$.uploadId'),json_extract(a.result_json,'$.packageId')))
        AND NOT EXISTS (SELECT 1 FROM staged_tables t WHERE t.id=json_extract(a.result_json,'$.tableId'))
        AND NOT EXISTS (SELECT 1 FROM import_plans p WHERE p.id=json_extract(a.result_json,'$.planId'))
        AND NOT EXISTS (SELECT 1 FROM prune_jobs p WHERE p.id=json_extract(a.result_json,'$.pruneId'))
        AND NOT EXISTS (SELECT 1 FROM export_grants g WHERE g.resource_id=json_extract(a.result_json,'$.resourceId'))
        AND NOT EXISTS (SELECT 1 FROM resource_leases l WHERE l.resource_id=json_extract(a.result_json,'$.resourceId'))
        """;

    public record Key(String actor,String scope,String id) {}
    public record RetentionCursor(long settled,Key key) {}
    public record Source(String packageId,String grant,boolean receivingUpload) {}
    public record Attempt(Key key,String procedure,String inputHash,String authorizationScope,String role,
                          Long expectedRevision,Long namespaceRevision,Source source,String uploadId,long claimDeadline,
                          String state,long updated,String cleanup,String unit,String result) {
        public Attempt cancelled(long now) {
            return new Attempt(key,procedure,inputHash,authorizationScope,role,expectedRevision,namespaceRevision,
                source,uploadId,claimDeadline,"CANCELLED_ROLLED_BACK",now,uploadId==null?cleanup:"pending",null,null);
        }
    }

    public OperationAttemptRepository(OperationalStore store) { this.store=store; }

    public static void initialize(Statement sql)throws SQLException {
        sql.execute("""
            CREATE TABLE operation_attempts (
                actor_id TEXT NOT NULL, scope TEXT NOT NULL, operation_id TEXT NOT NULL, procedure TEXT,
                input_hash TEXT, authorization_scope TEXT, role TEXT, expected_revision INTEGER, namespace_revision INTEGER,
                source_package_id TEXT, source_grant_id TEXT, receiving_upload INTEGER NOT NULL DEFAULT 0,
                upload_id TEXT, claim_deadline INTEGER NOT NULL, id_time INTEGER NOT NULL,
                state TEXT NOT NULL, updated_at INTEGER NOT NULL, cleanup_state TEXT NOT NULL DEFAULT 'none',
                control_pending INTEGER NOT NULL DEFAULT 0, result_json TEXT, settled_at INTEGER,
                unit_id TEXT UNIQUE, owner_kind TEXT, owner_id TEXT, previous_marker TEXT,
                prepared_state TEXT, prepared_result TEXT, workflow_only INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (actor_id,scope,operation_id)
            ) STRICT
            """);
        sql.execute("""
            CREATE UNIQUE INDEX operation_pending_owner ON operation_attempts(owner_kind,owner_id)
            WHERE prepared_state IS NOT NULL AND unit_id IS NOT NULL
            """);
        sql.execute("CREATE INDEX operation_pending ON operation_attempts(unit_id) WHERE unit_id IS NOT NULL");
        sql.execute("CREATE INDEX operation_upload ON operation_attempts(upload_id) WHERE upload_id IS NOT NULL");
        sql.execute("CREATE INDEX operation_open ON operation_attempts(state,claim_deadline) WHERE state IN ('REGISTERED','RUNNING','CANCEL_REQUESTED')");
        sql.execute("CREATE INDEX operation_registration_scan ON operation_attempts(operation_id,actor_id,scope) WHERE state='REGISTERED' AND control_pending=0");
        sql.execute("CREATE INDEX operation_retention ON operation_attempts(settled_at) WHERE settled_at IS NOT NULL");
        sql.execute("CREATE INDEX operation_cleanup ON operation_attempts(updated_at) WHERE cleanup_state='pending'");
    }

    public Attempt find(String actor,String scope,String id) { return find(new Key(actor,scope,id)); }

    public Attempt find(Key key) {
        return store.read(db -> {
            try(var query=db.prepareStatement("""
                    SELECT * FROM operation_attempts WHERE actor_id=? AND scope=? AND operation_id=?
                      AND control_pending<>1 AND workflow_only=0
                    """)) {
                bindKey(query,1,key);
                try(var row=query.executeQuery()) { return row.next()?read(row):null; }
            }
        });
    }

    public List<Attempt> uploadAttempts(String id) {
        return select("upload_id=? AND control_pending=0 AND workflow_only=0",id);
    }

    public void register(Attempt attempt) {
        store.write(0,db -> {
            try(var insert=db.prepareStatement("""
                    INSERT OR IGNORE INTO operation_attempts (actor_id,scope,operation_id,procedure,input_hash,
                        authorization_scope,role,expected_revision,namespace_revision,source_package_id,source_grant_id,
                        receiving_upload,upload_id,claim_deadline,id_time,state,updated_at,control_pending)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1)
                    """)) {
                bindKey(insert,1,attempt.key());
                insert.setString(4,attempt.procedure());
                insert.setString(5,attempt.inputHash());
                insert.setString(6,attempt.authorizationScope());
                insert.setString(7,attempt.role());
                insert.setObject(8,attempt.expectedRevision());
                insert.setObject(9,attempt.namespaceRevision());
                var source=attempt.source();
                insert.setString(10,source==null?null:source.packageId());
                insert.setString(11,source==null?null:source.grant());
                insert.setBoolean(12,source!=null&&source.receivingUpload());
                insert.setString(13,attempt.uploadId());
                insert.setLong(14,attempt.claimDeadline());
                insert.setLong(15,idTime(attempt.key().id()));
                insert.setString(16,attempt.state());
                insert.setLong(17,attempt.updated());
                if(insert.executeUpdate()!=1)
                    throw ExceptionHandler.fail("OUTCOME_UNKNOWN","Operation registration is awaiting settlement");
            }
            return null;
        });
    }

    public void settleRegistration(Key key,boolean committed,long now) {
        store.write(0,db -> {
            if(committed)try(var update=db.prepareStatement("""
                    UPDATE operation_attempts SET
                      settled_at=CASE WHEN state<>'REGISTERED' OR control_pending=2 THEN ? END,
                      updated_at=CASE WHEN control_pending=2 THEN ? ELSE updated_at END,
                      cleanup_state=CASE WHEN control_pending=2 AND upload_id IS NOT NULL THEN 'pending' ELSE cleanup_state END,
                      state=CASE WHEN control_pending=2 THEN 'CANCELLED_ROLLED_BACK' ELSE state END,control_pending=0
                    WHERE actor_id=? AND scope=? AND operation_id=? AND control_pending<>0
                    """)) {
                update.setLong(1,now);
                update.setLong(2,now);
                bindKey(update,3,key);
                update.executeUpdate();
            }else try(var delete=db.prepareStatement("""
                    DELETE FROM operation_attempts WHERE actor_id=? AND scope=? AND operation_id=? AND control_pending=1
                    """);
                var restore=db.prepareStatement("""
                    UPDATE operation_attempts SET control_pending=0
                    WHERE actor_id=? AND scope=? AND operation_id=? AND control_pending=2
                    """)) {
                bindKey(delete,1,key);
                delete.executeUpdate();
                bindKey(restore,1,key);
                restore.executeUpdate();
            }
            return null;
        });
    }

    public void recoverRegistrations(long now) {
        store.write(0,db -> {
            try(var update=db.prepareStatement("""
                    UPDATE operation_attempts SET control_pending=0,state='CANCELLED_ROLLED_BACK',
                        updated_at=?,settled_at=?,cleanup_state=CASE WHEN upload_id IS NULL THEN 'none' ELSE 'pending' END
                    WHERE control_pending<>0
                    """)) {
                update.setLong(1,now);
                update.setLong(2,now);
                update.executeUpdate();
            }
            return null;
        });
    }

    public boolean stageRegistrationCancellation(Key key) {
        return store.write(0,db -> {
            try(var update=db.prepareStatement("""
                    UPDATE operation_attempts SET control_pending=2
                    WHERE actor_id=? AND scope=? AND operation_id=? AND state='REGISTERED' AND control_pending=0
                    """)) {
                bindKey(update,1,key);
                return update.executeUpdate()==1;
            }
        });
    }

    public boolean cancel(Attempt attempt,boolean running,long now) {
        String next=running?"CANCEL_REQUESTED":"CANCELLED_ROLLED_BACK";
        return store.write(0,db -> {
            try(var update=db.prepareStatement("""
                    UPDATE operation_attempts SET state=?,updated_at=?,settled_at=?,
                        cleanup_state=CASE WHEN upload_id IS NULL THEN cleanup_state ELSE 'pending' END
                    WHERE actor_id=? AND scope=? AND operation_id=? AND state=? AND control_pending=0
                        AND (unit_id IS NULL OR ?=1)
                    """)) {
                update.setString(1,next);
                update.setLong(2,now);
                update.setObject(3,running?null:now);
                bindKey(update,4,attempt.key());
                update.setString(7,attempt.state());
                update.setBoolean(8,running);
                return update.executeUpdate()==1;
            }
        });
    }

    public List<Attempt> registered(Key after) {
        return store.read(db -> {
            var attempts=new ArrayList<Attempt>();
            try(var query=db.prepareStatement("""
                    SELECT * FROM operation_attempts WHERE state='REGISTERED' AND control_pending=0 AND
                      (operation_id,actor_id,scope)>(?,?,?) ORDER BY operation_id,actor_id,scope LIMIT 128
                    """)) {
                query.setString(1,after==null?"":after.id());
                query.setString(2,after==null?"":after.actor());
                query.setString(3,after==null?"":after.scope());
                try(var rows=query.executeQuery()) { while(rows.next())attempts.add(read(rows)); }
            }
            return attempts;
        });
    }

    public List<Attempt> cleanupCandidates() {
        return select("""
            cleanup_state='pending' AND upload_id IS NULL AND settled_at IS NOT NULL ORDER BY updated_at LIMIT 128
            """,null);
    }

    private List<Attempt> select(String predicate,String parameter) {
        return store.read(db -> {
            var attempts=new ArrayList<Attempt>();
            try(var query=db.prepareStatement("SELECT * FROM operation_attempts WHERE "+predicate)) {
                if(parameter!=null)query.setString(1,parameter);
                try(var rows=query.executeQuery()) { while(rows.next())attempts.add(read(rows)); }
            }
            return attempts;
        });
    }

    public boolean cleanUpload(String id,boolean success) {
        return store.write(0,db -> {
            String fields=success
                ? "cleanup_state=CASE WHEN cleanup_state IN ('pending','failed') THEN 'complete' ELSE cleanup_state END,upload_id=NULL"
                : "cleanup_state=CASE WHEN cleanup_state IN ('pending','failed') THEN 'failed' ELSE cleanup_state END";
            try(var update=db.prepareStatement("UPDATE operation_attempts SET "+fields+" WHERE upload_id=? AND settled_at IS NOT NULL");
                var query=db.prepareStatement("SELECT 1 FROM operation_attempts WHERE upload_id=? LIMIT 1")) {
                update.setString(1,id);
                update.executeUpdate();
                query.setString(1,id);
                try(var row=query.executeQuery()) { return !row.next(); }
            }
        });
    }

    public void cleanup(Attempt item,String state) {
        store.write(0,db -> {
            try(var update=db.prepareStatement("""
                    UPDATE operation_attempts SET cleanup_state=?
                    WHERE actor_id=? AND scope=? AND operation_id=? AND settled_at IS NOT NULL
                    """)) {
                update.setString(1,state);
                bindKey(update,2,item.key());
                update.executeUpdate();
            }
            return null;
        });
    }

    public List<RetentionCursor> expiryCandidates(long now,RetentionCursor after) {
        return store.read(db -> {
            var attempts=new ArrayList<RetentionCursor>();
            try(var query=db.prepareStatement("""
                    SELECT actor_id,scope,operation_id,settled_at FROM operation_attempts a WHERE
                    """+EXPIRABLE+"""
                    AND (settled_at,actor_id,scope,operation_id)>(?,?,?,?)
                    ORDER BY settled_at,actor_id,scope,operation_id LIMIT 128
                    """)) {
                query.setLong(1,now-RETENTION);
                query.setLong(2,now);
                bindCursor(query,3,after);
                try(var rows=query.executeQuery()) {
                    while(rows.next())attempts.add(new RetentionCursor(rows.getLong(4),
                        new Key(rows.getString(1),rows.getString(2),rows.getString(3))));
                }
            }
            return attempts;
        });
    }

    public void collect(List<RetentionCursor> attempts,List<OperationReceiptRepository.Expiry> receipts,long now) {
        if(attempts.isEmpty()&&receipts.isEmpty())return;
        store.write(0,db -> {
            var admission=OperationalStateRepository.admission(db);
            if(now<admission.observedTime())return null;
            long fence=admission.rejectedThrough();
            try(var receipt=db.prepareStatement("""
                    DELETE FROM operation_receipts WHERE actor_id=? AND scope=? AND operation_id=? AND unit_id IS NULL
                    """);
                var attempt=db.prepareStatement("""
                    DELETE FROM operation_attempts AS a WHERE actor_id=? AND scope=? AND operation_id=? AND
                    """+EXPIRABLE)) {
                for(var item:attempts) {
                    bindKey(attempt,1,item.key());
                    attempt.setLong(4,now-RETENTION);
                    attempt.setLong(5,now);
                    if(attempt.executeUpdate()==0)continue;
                    fence=Math.max(fence,idTime(item.key().id()));
                    bindKey(receipt,1,item.key());
                    receipt.executeUpdate();
                }
            }
            fence=OperationReceiptRepository.collectStandalone(db,receipts,now-RETENTION,fence);
            OperationalStateRepository.advance(db,fence,now);
            return null;
        });
    }

    private static Attempt read(ResultSet row)throws SQLException {
        var key=new Key(row.getString("actor_id"),row.getString("scope"),row.getString("operation_id"));
        String packageId=row.getString("source_package_id");
        Source source=packageId==null?null:new Source(packageId,row.getString("source_grant_id"),row.getBoolean("receiving_upload"));
        long expected=row.getLong("expected_revision");
        Long expectedRevision=row.wasNull()?null:expected;
        long namespace=row.getLong("namespace_revision");
        Long namespaceRevision=row.wasNull()?null:namespace;
        return new Attempt(key,row.getString("procedure"),row.getString("input_hash"),row.getString("authorization_scope"),
            row.getString("role"),expectedRevision,namespaceRevision,source,row.getString("upload_id"),row.getLong("claim_deadline"),
            row.getString("state"),row.getLong("updated_at"),row.getString("cleanup_state"),row.getString("unit_id"),row.getString("result_json"));
    }

    static void bindKey(PreparedStatement sql,int index,Key key)throws SQLException {
        sql.setString(index,key.actor());
        sql.setString(index+1,key.scope());
        sql.setString(index+2,key.id());
    }

    static void bindCursor(PreparedStatement sql,int index,RetentionCursor after)throws SQLException {
        sql.setLong(index,after==null?Long.MIN_VALUE:after.settled());
        bindKey(sql,index+1,after==null?new Key("","",""):after.key());
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

    public static boolean open(String state) {
        return "REGISTERED".equals(state)||"RUNNING".equals(state)||"CANCEL_REQUESTED".equals(state);
    }
}
