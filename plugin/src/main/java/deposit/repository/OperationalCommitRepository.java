package deposit.repository;

import deposit.common.ExceptionHandler;
import deposit.common.OperationalStore;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Pending state, bounded graph witness ownership and short SQLite settlement transactions. */
public final class OperationalCommitRepository {
    public static final String MARKER="_depositCommitUnit";
    private final OperationalStore store;

    public OperationalCommitRepository(OperationalStore store) { this.store=store; }

    public record Unit(String id,String actor,String scope,String operation,String inputHash,
                       String ownerKind,String ownerId) {}
    public record Pending(String id,String ownerKind,String ownerId,String previousMarker,boolean prepared,
                          OperationAttemptRepository.Key key) {}
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
                OperationAttemptRepository.bindKey(update,5,new OperationAttemptRepository.Key(unit.actor(),unit.scope(),unit.operation()));
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
                    (actor_id,scope,operation_id,input_hash,owner_kind,owner_id,unit_id,workflow_only,
                     state,claim_deadline,id_time,updated_at)
                    VALUES (?,?,?,?,?,?,?,1,'RUNNING',0,-1,0)
                    """)) {
                insert.setString(1,unit.actor());
                insert.setString(2,unit.scope());
                insert.setString(3,unit.operation());
                insert.setString(4,unit.inputHash());
                insert.setString(5,unit.ownerKind());
                insert.setString(6,unit.ownerId());
                insert.setString(7,unit.id());
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
                OperationAttemptRepository.bindKey(query,1,new OperationAttemptRepository.Key(actor,scope,operation));
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
                    new OperationAttemptRepository.Key(rows.getString(6),rows.getString(7),rows.getString(8))));
            }
            return pending;
        });
    }

    public void settle(String unit,boolean committed,long now,boolean cancelled,boolean outputIntent) {
        store.write(0,db -> {
            boolean workflow;
            try(var query=db.prepareStatement("SELECT workflow_only,prepared_state FROM operation_attempts WHERE unit_id=?")) {
                query.setString(1,unit);
                try(var row=query.executeQuery()) {
                    if(!row.next())return null; // Already settled.
                    workflow=row.getBoolean(1);
                    if(committed&&row.getString(2)==null)throw new SQLException("Missing prepared operation result");
                }
            }
            ExchangeRepository.settle(db,unit,committed);
            ExportGrantRepository.settle(db,unit,committed);
            ImportResultStore.settle(db,unit,committed);
            OperationReceiptRepository.settle(db,unit,committed,now);
            StagedTableRepository.settle(db,unit,committed);
            ImportPlanRepository.settle(db,unit,committed);
            ResourceTransferStore.settle(db,unit,committed);
            PruneRepository.settle(db,unit,committed);
            if(workflow)try(var delete=db.prepareStatement("DELETE FROM operation_attempts WHERE unit_id=?")) {
                delete.setString(1,unit);
                delete.executeUpdate();
            }else try(var update=db.prepareStatement("""
                    UPDATE operation_attempts SET
                      state=CASE WHEN ?=1 THEN prepared_state
                        WHEN ?=1 OR state='CANCEL_REQUESTED' THEN 'CANCELLED_ROLLED_BACK' ELSE 'FAILED_ROLLED_BACK' END,
                      result_json=CASE WHEN ?=1 THEN prepared_result ELSE NULL END,
                      cleanup_state=CASE WHEN ?=0 AND (upload_id IS NOT NULL OR ?=1) THEN 'pending' ELSE 'none' END,
                      updated_at=?,settled_at=?,unit_id=NULL,owner_kind=NULL,owner_id=NULL,previous_marker=NULL,
                      prepared_state=NULL,prepared_result=NULL WHERE unit_id=?
                    """)) {
                update.setBoolean(1,committed);
                update.setBoolean(2,cancelled);
                update.setBoolean(3,committed);
                update.setBoolean(4,committed);
                update.setBoolean(5,outputIntent);
                update.setLong(6,now);
                update.setLong(7,now);
                update.setString(8,unit);
                if(update.executeUpdate()!=1)throw new SQLException("Operation ownership changed during settlement");
            }
            return null;
        });
    }
}
