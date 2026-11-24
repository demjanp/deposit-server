package deposit.repository;

import deposit.common.ExceptionHandler;
import deposit.common.ImportResultContract;
import deposit.common.Results.Outcome;
import java.nio.charset.StandardCharsets;
import deposit.common.OperationalStore;
import deposit.common.RequestContext;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable operation attempts, receipts and their enclosing commit units. */
public final class OperationRepository {
    public static final String MARKER="_depositCommitUnit";
    public static final long RETENTION=86_400_000L;
    public static final long FUTURE_SKEW=300_000L;
    private final OperationalStore store;
    private static final String EXPIRABLE = """
        a.settled_at<=? AND a.id_time<=? AND (a.claim_deadline IS NULL OR a.claim_deadline<=?)
        AND a.unit_id IS NULL AND a.receipt_unit_id IS NULL AND a.control_pending=0
        AND (a.state IS NULL OR a.state NOT IN ('REGISTERED','RUNNING','CANCEL_REQUESTED'))
        AND a.cleanup_state NOT IN ('pending','failed')
        AND NOT EXISTS (SELECT 1 FROM import_results r
            WHERE r.id IN (a.import_result_id,json_extract(a.result_json,'$.resultId')) AND r.abandoned=0)
        AND NOT EXISTS (SELECT 1 FROM resource_transfers t WHERE
            t.id IN (a.upload_id,json_extract(a.result_json,'$.uploadId'))
            OR t.resource_id=json_extract(a.result_json,'$.resourceId'))
        AND NOT EXISTS (SELECT 1 FROM exchange_stages e WHERE
            e.id IN (a.upload_id,json_extract(a.result_json,'$.uploadId'),json_extract(a.result_json,'$.packageId')))
        AND NOT EXISTS (SELECT 1 FROM staged_tables t WHERE t.id=json_extract(a.result_json,'$.tableId'))
        AND NOT EXISTS (SELECT 1 FROM import_plans p WHERE p.id=json_extract(a.result_json,'$.planId'))
        AND NOT EXISTS (SELECT 1 FROM prune_jobs p WHERE p.id=json_extract(a.result_json,'$.pruneId'))
        AND NOT EXISTS (SELECT 1 FROM export_grants g WHERE g.resource_id=json_extract(a.result_json,'$.resourceId'))
        AND NOT EXISTS (SELECT 1 FROM staged_tables t WHERE t.resource_id=json_extract(a.result_json,'$.resourceId'))
        """;

    private static final String VISIBLE_RECEIPT = """
        scope=? AND operation_id=? AND receipt_revision IS NOT NULL AND (receipt_unit_id IS NULL OR receipt_unit_id=?)
        """;

    public record Key(String actor,String scope,String id) {}
    public record RetentionCursor(long settled,Key key) {}
    public record Receipt(String actor,String scope,String operation,String requestHash,long revision,
                          boolean administrativeMode,String deletedNamespaceId,String importResultId,
                          String resultKind,String resultNamespace,boolean resultCollected) {}
    public record Source(String packageId,String grant,boolean receivingUpload) {}
    public record Attempt(Key key,String procedure,String inputHash,String authorizationScope,String role,
                          Long expectedRevision,Long namespaceRevision,Source source,String uploadId,long claimDeadline,
                          String state,long updated,String cleanup,String unit,String result) {
        public Attempt cancelled(long now) {
            return new Attempt(key,procedure,inputHash,authorizationScope,role,expectedRevision,namespaceRevision,
                source,uploadId,claimDeadline,"CANCELLED_ROLLED_BACK",now,uploadId==null?cleanup:"pending",null,null);
        }
    }

    public OperationRepository(OperationalStore store) { this.store=store; }

    public static void initialize(Statement sql)throws SQLException {
        // A claim deadline identifies a public attempt, and a receipt revision identifies a receipt.
        // unit_id identifies its unique owner. receipt_unit_id can link multiple receipts to that owner.
        sql.execute("""
            CREATE TABLE operation_attempts (
                actor_id TEXT NOT NULL, scope TEXT NOT NULL, operation_id TEXT NOT NULL, procedure TEXT,
                input_hash TEXT, authorization_scope TEXT, role TEXT, expected_revision INTEGER, namespace_revision INTEGER,
                source_package_id TEXT, source_grant_id TEXT, receiving_upload INTEGER NOT NULL DEFAULT 0,
                upload_id TEXT, claim_deadline INTEGER, id_time INTEGER NOT NULL,
                state TEXT, updated_at INTEGER, cleanup_state TEXT NOT NULL DEFAULT 'none',
                control_pending INTEGER NOT NULL DEFAULT 0, result_json TEXT, settled_at INTEGER,
                unit_id TEXT UNIQUE, owner_kind TEXT, owner_id TEXT, previous_marker TEXT,
                prepared_state TEXT, prepared_result TEXT,
                receipt_revision INTEGER, receipt_hash TEXT, administrative_mode INTEGER, deleted_namespace_id TEXT,
                import_result_id TEXT, result_kind TEXT, result_namespace_id TEXT,
                result_collected INTEGER NOT NULL DEFAULT 0, receipt_unit_id TEXT,
                PRIMARY KEY (actor_id,scope,operation_id)
            ) STRICT
            """);
        sql.execute("""
            CREATE UNIQUE INDEX operation_pending_owner ON operation_attempts(owner_kind,owner_id)
            WHERE prepared_state IS NOT NULL AND unit_id IS NOT NULL
            """);
        sql.execute("CREATE INDEX operation_receipt_scope ON operation_attempts(scope,operation_id) WHERE receipt_revision IS NOT NULL");
        sql.execute("CREATE INDEX operation_receipt_pending ON operation_attempts(receipt_unit_id) WHERE receipt_unit_id IS NOT NULL");
        sql.execute("CREATE INDEX operation_upload ON operation_attempts(upload_id) WHERE upload_id IS NOT NULL");
        sql.execute("CREATE INDEX operation_open ON operation_attempts(state,claim_deadline) WHERE state IN ('REGISTERED','RUNNING','CANCEL_REQUESTED')");
        sql.execute("CREATE INDEX operation_registration_scan ON operation_attempts(operation_id,actor_id,scope) WHERE state='REGISTERED' AND control_pending=0");
        sql.execute("CREATE INDEX operation_retention ON operation_attempts(settled_at,actor_id,scope,operation_id) WHERE settled_at IS NOT NULL");
        sql.execute("CREATE INDEX operation_cleanup ON operation_attempts(updated_at) WHERE cleanup_state='pending'");
    }

    public Attempt find(String actor,String scope,String id) { return find(new Key(actor,scope,id)); }

    public Attempt find(Key key) {
        return store.read(db -> {
            try(var query=db.prepareStatement("""
                    SELECT a.*, CASE WHEN receipt_unit_id IS NULL AND result_collected=0
                        AND import_result_id=json_extract(result_json,'$.resultId')
                        THEN json_set(result_json,'$.collections',json((%s)))
                        ELSE result_json END AS outcome
                    FROM operation_attempts a WHERE actor_id=? AND scope=? AND operation_id=?
                      AND control_pending<>1 AND claim_deadline IS NOT NULL
                    """.formatted(ImportResultStore.ADVERTISED_DESCRIPTORS.formatted("a.import_result_id")))) {
                bindKey(query,1,key);
                try(var row=query.executeQuery()) { return row.next()?read(row,row.getString("outcome")):null; }
            }
        });
    }

    public List<Attempt> uploadAttempts(String id) {
        return select("upload_id=? AND control_pending=0",id);
    }

    public void register(Attempt attempt) {
        store.write(0,db -> {
            try(var insert=db.prepareStatement("""
                    INSERT INTO operation_attempts (actor_id,scope,operation_id,procedure,input_hash,
                        authorization_scope,role,expected_revision,namespace_revision,source_package_id,source_grant_id,
                        receiving_upload,upload_id,claim_deadline,id_time,state,updated_at,control_pending)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1)
                    ON CONFLICT(actor_id,scope,operation_id) DO UPDATE SET
                      receipt_hash=CASE WHEN input_hash IS NOT excluded.input_hash THEN input_hash END,
                      procedure=excluded.procedure,input_hash=excluded.input_hash,
                      authorization_scope=excluded.authorization_scope,role=excluded.role,
                      expected_revision=excluded.expected_revision,namespace_revision=excluded.namespace_revision,
                      source_package_id=excluded.source_package_id,source_grant_id=excluded.source_grant_id,
                      receiving_upload=excluded.receiving_upload,upload_id=excluded.upload_id,
                      claim_deadline=excluded.claim_deadline,state=excluded.state,updated_at=excluded.updated_at,
                      control_pending=excluded.control_pending
                    WHERE state IS NULL
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
                      settled_at=CASE WHEN state<>'REGISTERED' OR control_pending=2
                        THEN max(coalesce(settled_at,?1),?1) ELSE settled_at END,
                      updated_at=CASE WHEN control_pending=2 THEN ? ELSE updated_at END,
                      cleanup_state=CASE WHEN control_pending=2 AND upload_id IS NOT NULL THEN 'pending' ELSE cleanup_state END,
                      state=CASE WHEN control_pending=2 THEN 'CANCELLED_ROLLED_BACK' ELSE state END,control_pending=0
                    WHERE actor_id=? AND scope=? AND operation_id=? AND control_pending<>0
                    """)) {
                update.setLong(1,now);
                update.setLong(2,now);
                bindKey(update,3,key);
                update.executeUpdate();
            }else {
                discardAttempt(db,key,"control_pending=1");
                try(var restore=db.prepareStatement("""
                        UPDATE operation_attempts SET control_pending=0
                        WHERE actor_id=? AND scope=? AND operation_id=? AND control_pending=2
                        """)) {
                    bindKey(restore,1,key);
                    restore.executeUpdate();
                }
            }
            return null;
        });
    }

    public void recoverRegistrations(long now) {
        store.write(0,db -> {
            try(var update=db.prepareStatement("""
                    UPDATE operation_attempts SET control_pending=0,state='CANCELLED_ROLLED_BACK',
                        updated_at=?,settled_at=max(coalesce(settled_at,?2),?2),cleanup_state=CASE WHEN upload_id IS NULL THEN 'none' ELSE 'pending' END
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
                    UPDATE operation_attempts SET state=?,updated_at=?,
                        settled_at=CASE WHEN ?3 IS NULL THEN settled_at ELSE max(coalesce(settled_at,?3),?3) END,
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
            cleanup_state='pending' AND upload_id IS NULL AND unit_id IS NULL ORDER BY updated_at LIMIT 128
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
            try(var update=db.prepareStatement("UPDATE operation_attempts SET "+fields+" "+"""
                    WHERE upload_id=? AND claim_deadline IS NOT NULL AND control_pending=0 AND unit_id IS NULL
                      AND state NOT IN ('REGISTERED','RUNNING','CANCEL_REQUESTED')
                    """);
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
                    WHERE actor_id=? AND scope=? AND operation_id=? AND claim_deadline IS NOT NULL
                      AND control_pending=0 AND unit_id IS NULL AND settled_at IS NOT NULL
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
                query.setLong(2,now-RETENTION);
                query.setLong(3,now);
                bindCursor(query,4,after);
                try(var rows=query.executeQuery()) {
                    while(rows.next())attempts.add(new RetentionCursor(rows.getLong(4),
                        new Key(rows.getString(1),rows.getString(2),rows.getString(3))));
                }
            }
            return attempts;
        });
    }

    public void collect(List<RetentionCursor> expired,long now) {
        if(expired.isEmpty())return;
        store.write(0,db -> {
            var admission=OperationalStateRepository.admission(db);
            if(now<admission.observedTime())return null;
            long fence=admission.rejectedThrough();
            try(var delete=db.prepareStatement("""
                    DELETE FROM operation_attempts AS a WHERE actor_id=? AND scope=? AND operation_id=? AND
                    """+EXPIRABLE+" RETURNING id_time")) {
                for(var item:expired) {
                    bindKey(delete,1,item.key());
                    delete.setLong(4,now-RETENTION);
                    delete.setLong(5,now-RETENTION);
                    delete.setLong(6,now);
                    try(var row=delete.executeQuery()) {
                        if(row.next())fence=Math.max(fence,row.getLong(1));
                    }
                }
            }
            OperationalStateRepository.advance(db,fence,now);
            return null;
        });
    }

    private static void discardAttempt(Connection db,Key key,String predicate)throws SQLException {
        try(var delete=db.prepareStatement("""
                DELETE FROM operation_attempts
                WHERE actor_id=? AND scope=? AND operation_id=? AND receipt_revision IS NULL AND
                """+predicate);
            var clear=db.prepareStatement("""
                UPDATE operation_attempts SET
                  input_hash=coalesce(receipt_hash,input_hash),receipt_hash=NULL,procedure=NULL,authorization_scope=NULL,
                  role=NULL,expected_revision=NULL,namespace_revision=NULL,source_package_id=NULL,source_grant_id=NULL,
                  receiving_upload=0,upload_id=NULL,claim_deadline=NULL,state=NULL,updated_at=NULL,control_pending=0,
                  unit_id=NULL,owner_kind=NULL,owner_id=NULL,previous_marker=NULL,prepared_state=NULL,prepared_result=NULL
                WHERE actor_id=? AND scope=? AND operation_id=? AND
                """+predicate)) {
            bindKey(delete,1,key);
            if(delete.executeUpdate()==0) {
                bindKey(clear,1,key);
                clear.executeUpdate();
            }
        }
    }

    public Receipt findReceipt(String actor,String scope,String operation,String unit) {
        Objects.requireNonNull(actor);
        return store.read(db -> {
            try(var query=db.prepareStatement("""
                    SELECT coalesce(receipt_hash,input_hash) AS request_hash,receipt_revision,administrative_mode,
                        deleted_namespace_id,import_result_id,result_kind,result_namespace_id,result_collected
                    FROM operation_attempts WHERE
                    """+VISIBLE_RECEIPT+" AND actor_id=?")) {
                bindReceipt(query,scope,operation,unit);
                query.setString(4,actor);
                try(var row=query.executeQuery()) {
                    return row.next()?new Receipt(actor,scope,operation,
                        row.getString("request_hash"),row.getLong("receipt_revision"),row.getBoolean("administrative_mode"),
                        row.getString("deleted_namespace_id"),row.getString("import_result_id"),row.getString("result_kind"),
                        row.getString("result_namespace_id"),row.getBoolean("result_collected")):null;
                }
            }
        });
    }

    public boolean hasReceipt(String scope,String operation,String unit) {
        return store.read(db -> {
            try(var query=db.prepareStatement("SELECT 1 FROM operation_attempts WHERE "+VISIBLE_RECEIPT+" LIMIT 1")) {
                bindReceipt(query,scope,operation,unit);
                try(var row=query.executeQuery()) { return row.next(); }
            }
        });
    }

    private static void bindReceipt(PreparedStatement query,String scope,String operation,String unit)throws SQLException {
        query.setString(1,scope);
        query.setString(2,operation);
        query.setString(3,unit);
    }

    public void saveReceipt(RequestContext context,String scope,String hash,Outcome outcome,String deletedNamespace,String unit) {
        boolean result=ImportResultContract.FORMAT.equals(outcome.data().get("format"));
        var key=new Key(context.actorId,scope,context.operationId);
        store.write(0,db -> {
            try(var insert=db.prepareStatement("""
                    INSERT INTO operation_attempts(actor_id,scope,operation_id,input_hash,receipt_revision,administrative_mode,
                        deleted_namespace_id,import_result_id,result_kind,result_namespace_id,receipt_unit_id,id_time)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(actor_id,scope,operation_id) DO UPDATE SET
                      input_hash=CASE WHEN state IS NULL THEN excluded.input_hash ELSE input_hash END,
                      receipt_hash=CASE WHEN state IS NOT NULL AND input_hash IS NOT excluded.input_hash THEN excluded.input_hash END,
                      receipt_revision=excluded.receipt_revision,administrative_mode=excluded.administrative_mode,
                      deleted_namespace_id=excluded.deleted_namespace_id,import_result_id=excluded.import_result_id,
                      result_kind=excluded.result_kind,result_namespace_id=excluded.result_namespace_id,
                      receipt_unit_id=excluded.receipt_unit_id
                    WHERE receipt_revision IS NULL
                    """)) {
                bindKey(insert,1,key);
                insert.setString(4,hash);
                insert.setLong(5,context.revision);
                insert.setBoolean(6,context.state.scoped?context.state.administrative
                    :context.session!=null&&context.session.administrative);
                insert.setString(7,deletedNamespace);
                insert.setString(8,result?(String)outcome.data().get("resultId"):null);
                insert.setString(9,result?(String)outcome.data().get("kind"):null);
                insert.setString(10,result?(String)outcome.data().get("namespaceId"):null);
                insert.setString(11,unit);
                var operation=UUID.fromString(key.id());
                insert.setLong(12,operation.version()==7&&operation.variant()==2
                    ?operation.getMostSignificantBits()>>>16:-1);
                if(insert.executeUpdate()!=1)throw new SQLException("Operation receipt already exists");
            }
            return null;
        });
    }

    static void markResultCollected(Connection db,ImportResultStore.Root result,String descriptors)throws SQLException {
        try(var update=db.prepareStatement("""
                UPDATE operation_attempts SET result_collected=1, result_json=CASE
                    WHEN import_result_id=json_extract(result_json,'$.resultId')
                    THEN json_set(result_json,'$.collections',json(?)) ELSE result_json END
                WHERE actor_id=? AND scope=? AND operation_id=? AND import_result_id=?
                  AND result_namespace_id=? AND result_kind=? AND receipt_unit_id IS NULL
                """)) {
            update.setString(1,descriptors);
            bindKey(update,2,new Key(result.actor(),"server".equals(result.scope())?"server":result.namespace(),result.operation()));
            update.setString(5,result.id());
            update.setString(6,result.namespace());
            update.setString(7,result.kind());
            update.executeUpdate();
        }
    }

    private static void settleReceipts(Connection db,String unit,boolean committed,long now)throws SQLException {
        if(committed)try(var update=db.prepareStatement("""
                UPDATE operation_attempts SET receipt_unit_id=NULL,settled_at=max(coalesce(settled_at,?1),?1)
                WHERE receipt_unit_id=?
                """)) {
            update.setLong(1,now);
            update.setString(2,unit);
            update.executeUpdate();
        }else try(var delete=db.prepareStatement("DELETE FROM operation_attempts WHERE receipt_unit_id=? AND state IS NULL");
            var clear=db.prepareStatement("""
                UPDATE operation_attempts SET receipt_unit_id=NULL,receipt_revision=NULL,receipt_hash=NULL,
                  administrative_mode=NULL,deleted_namespace_id=NULL,import_result_id=NULL,result_kind=NULL,
                  result_namespace_id=NULL,result_collected=0 WHERE receipt_unit_id=?
                """)) {
            delete.setString(1,unit);
            delete.executeUpdate();
            clear.setString(1,unit);
            clear.executeUpdate();
        }
    }

    public record Unit(String id,String actor,String scope,String operation,String inputHash,
                       String ownerKind,String ownerId) {
        public Key key() { return new Key(actor,scope,operation); }
    }
    public record Pending(String id,String ownerKind,String ownerId,String previousMarker,boolean prepared,
                          Key key) {}
    public record Completion(String state,String result,boolean settled) {}

    public boolean begin(Unit unit,long now) {
        return store.write(0,db -> {
            try(var update=db.prepareStatement("""
                    UPDATE operation_attempts SET unit_id=?,owner_kind=?,owner_id=?,state='RUNNING',updated_at=?
                    WHERE actor_id=? AND scope=? AND operation_id=? AND input_hash=? AND control_pending=0
                        AND state='REGISTERED' AND claim_deadline>?
                    """)) {
                update.setString(1,unit.id());
                update.setString(2,unit.ownerKind());
                update.setString(3,unit.ownerId());
                update.setLong(4,now);
                bindKey(update,5,unit.key());
                update.setString(8,unit.inputHash());
                update.setLong(9,now);
                return update.executeUpdate()==1;
            }
        });
    }

    public void beginWorkflow(Unit unit) {
        store.write(0,db -> {
            try(var insert=db.prepareStatement("""
                    INSERT INTO operation_attempts
                    (actor_id,scope,operation_id,owner_kind,owner_id,unit_id,state,id_time)
                    VALUES (?,?,?,?,?,?,'RUNNING',-1)
                    """)) {
                bindKey(insert,1,unit.key());
                insert.setString(4,unit.ownerKind());
                insert.setString(5,unit.ownerId());
                insert.setString(6,unit.id());
                insert.executeUpdate();
            }
            return null;
        });
    }

    public void prepare(Unit unit,String previousMarker,String state,String result) {
        store.write(result.getBytes(StandardCharsets.UTF_8).length,db -> {
            try(var update=db.prepareStatement("""
                    UPDATE operation_attempts SET previous_marker=?,prepared_state=?,prepared_result=?
                    WHERE unit_id=? AND prepared_state IS NULL AND state='RUNNING'
                    """)) {
                update.setString(1,previousMarker);
                update.setString(2,state);
                update.setString(3,result);
                update.setString(4,unit.id());
                if(update.executeUpdate()!=1)throw new SQLException("Operation intent unavailable at commit");
            }
            return null;
        });
    }

    public void requireSettled(String kind,String owner,String currentUnit) {
        boolean pending=store.read(db -> {
            String sql="""
                SELECT 1 FROM operation_attempts
                WHERE owner_kind=? AND prepared_state IS NOT NULL AND unit_id IS NOT NULL AND unit_id IS NOT ?
                """;
            if(owner!=null)sql+=" AND owner_id=?";
            try(var query=db.prepareStatement(sql+" LIMIT 1")) {
                query.setString(1,kind);
                query.setString(2,currentUnit);
                if(owner!=null)query.setString(3,owner);
                try(var row=query.executeQuery()) { return row.next(); }
            }
        });
        if(pending)throw ExceptionHandler.fail("OUTCOME_UNKNOWN","An operation in this scope is awaiting settlement");
    }

    public Completion completion(String actor,String scope,String operation) {
        return store.read(db -> {
            try(var query=db.prepareStatement("""
                    SELECT state,result_json,unit_id FROM operation_attempts
                    WHERE actor_id=? AND scope=? AND operation_id=? AND (unit_id IS NOT NULL OR result_json IS NOT NULL)
                    """)) {
                bindKey(query,1,new Key(actor,scope,operation));
                try(var row=query.executeQuery()) {
                    if(!row.next())return null;
                    return row.getString(3)==null?new Completion(row.getString(1),row.getString(2),true):new Completion(null,null,false);
                }
            }
        });
    }

    public List<Pending> pending() {
        return store.read(db -> {
            var pending=new ArrayList<Pending>();
            try(var query=db.prepareStatement("""
                    SELECT unit_id,owner_kind,owner_id,previous_marker,prepared_state IS NOT NULL,actor_id,scope,operation_id
                    FROM operation_attempts WHERE unit_id IS NOT NULL
                    """);var rows=query.executeQuery()) {
                while(rows.next())pending.add(new Pending(rows.getString(1),rows.getString(2),
                    rows.getString(3),rows.getString(4),rows.getBoolean(5),
                    new Key(rows.getString(6),rows.getString(7),rows.getString(8))));
            }
            return pending;
        });
    }

    public void settle(String unit,boolean committed,long now,boolean cancelled,boolean outputIntent) {
        store.write(0,db -> {
            Key workflow=null;
            try(var query=db.prepareStatement("""
                    SELECT claim_deadline IS NULL,prepared_state,actor_id,scope,operation_id
                    FROM operation_attempts WHERE unit_id=?
                    """)) {
                query.setString(1,unit);
                try(var row=query.executeQuery()) {
                    if(!row.next())return null; // Already settled.
                    if(row.getBoolean(1))workflow=new Key(row.getString(3),row.getString(4),row.getString(5));
                    if(committed&&row.getString(2)==null)throw new SQLException("Missing prepared operation result");
                }
            }
            ExchangeRepository.settle(db,unit,committed);
            ExportGrantRepository.settle(db,unit,committed);
            ImportResultStore.settle(db,unit,committed);
            settleReceipts(db,unit,committed,now);
            StagedTableRepository.settle(db,unit,committed);
            ImportPlanRepository.settle(db,unit,committed);
            ResourceTransferStore.settle(db,unit,committed);
            PruneRepository.settle(db,unit,committed);
            if(workflow!=null) {
                discardAttempt(db,workflow,"claim_deadline IS NULL");
            }else try(var update=db.prepareStatement("""
                    UPDATE operation_attempts SET
                      state=CASE WHEN ?=1 THEN prepared_state
                        WHEN ?=1 OR state='CANCEL_REQUESTED' THEN 'CANCELLED_ROLLED_BACK' ELSE 'FAILED_ROLLED_BACK' END,
                      result_json=CASE WHEN ?=1 THEN prepared_result ELSE NULL END,
                      cleanup_state=CASE WHEN ?=0 AND (upload_id IS NOT NULL OR ?=1) THEN 'pending' ELSE 'none' END,
                      updated_at=?,settled_at=max(coalesce(settled_at,?6),?6),unit_id=NULL,owner_kind=NULL,owner_id=NULL,previous_marker=NULL,
                      prepared_state=NULL,prepared_result=NULL WHERE unit_id=?
                    """)) {
                update.setBoolean(1,committed);
                update.setBoolean(2,cancelled);
                update.setBoolean(3,committed);
                update.setBoolean(4,committed);
                update.setBoolean(5,outputIntent);
                update.setLong(6,now);
                update.setString(7,unit);
                if(update.executeUpdate()!=1)throw new SQLException("Operation ownership changed during settlement");
            }
            return null;
        });
    }

    private static Attempt read(ResultSet row)throws SQLException {
        return read(row,row.getString("result_json"));
    }

    private static Attempt read(ResultSet row,String result)throws SQLException {
        var key=new Key(row.getString("actor_id"),row.getString("scope"),row.getString("operation_id"));
        String packageId=row.getString("source_package_id");
        Source source=packageId==null?null:new Source(packageId,row.getString("source_grant_id"),row.getBoolean("receiving_upload"));
        long expected=row.getLong("expected_revision");
        Long expectedRevision=row.wasNull()?null:expected;
        long namespace=row.getLong("namespace_revision");
        Long namespaceRevision=row.wasNull()?null:namespace;
        return new Attempt(key,row.getString("procedure"),row.getString("input_hash"),row.getString("authorization_scope"),
            row.getString("role"),expectedRevision,namespaceRevision,source,row.getString("upload_id"),row.getLong("claim_deadline"),
            row.getString("state"),row.getLong("updated_at"),row.getString("cleanup_state"),row.getString("unit_id"),result);
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
