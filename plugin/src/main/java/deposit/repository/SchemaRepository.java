package deposit.repository;

import java.util.*;
import org.neo4j.graphdb.*;

/** Startup-only schema provisioning, separate from all public actions. */
public final class SchemaRepository {
    private SchemaRepository() {}
    public static void initialize(GraphDatabaseService db) {
        try(var tx=db.beginTx()) {
            for(String label:List.of("Revision","DepositSnapshot","DepositHistoryEpoch"))tx.execute("CREATE CONSTRAINT deposit_"+label+"_identity IF NOT EXISTS FOR (n:"+label+") REQUIRE (n.namespaceId,n.id) IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_revision_touch IF NOT EXISTS FOR (n:RevisionTouch) REQUIRE (n.namespaceId,n.revisionId,n.entityKey) IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_revision_dependency IF NOT EXISTS FOR (n:RevisionDependency) REQUIRE (n.namespaceId,n.revisionId,n.entityKey) IS UNIQUE").close();
            tx.execute("CREATE INDEX deposit_revision_touch_epoch IF NOT EXISTS FOR (n:RevisionTouch) ON (n.namespaceId,n.historyEpochId)").close();
            tx.execute("CREATE INDEX deposit_revision_dependency_epoch IF NOT EXISTS FOR (n:RevisionDependency) ON (n.namespaceId,n.historyEpochId)").close();
            tx.execute("CREATE CONSTRAINT deposit_revision_tag IF NOT EXISTS FOR (n:RevisionTag) REQUIRE (n.namespaceId,n.revisionId) IS UNIQUE").close();
            tx.execute("CREATE INDEX deposit_revision_tag_epoch IF NOT EXISTS FOR (n:RevisionTag) ON (n.namespaceId,n.historyEpochId)").close();
            tx.execute("CREATE INDEX deposit_revision_sequence IF NOT EXISTS FOR (n:Revision) ON (n.namespaceId,n.sequence)").close();
            tx.execute("CREATE INDEX deposit_revision_epoch_sequence IF NOT EXISTS FOR (n:Revision) ON (n.namespaceId,n.historyEpochId,n.sequence)").close();
            tx.execute("CREATE INDEX deposit_revision_epoch IF NOT EXISTS FOR (n:Revision) ON (n.namespaceId,n.historyEpochId)").close();
            tx.execute("CREATE INDEX deposit_expired_history_epoch IF NOT EXISTS FOR (n:DepositHistoryEpoch) ON (n.namespaceId,n.expired,n.startedSequence)").close();
            tx.execute("CREATE INDEX deposit_snapshot_epoch IF NOT EXISTS FOR (n:DepositSnapshot) ON (n.namespaceId,n.historyEpochId)").close();
            for(String label:List.of("Class","Object","Descriptor","ClassDescriptor","Pattern","UserTool","DGeometry","DResource")) {
                tx.execute("CREATE CONSTRAINT deposit_"+label+"_id IF NOT EXISTS FOR (n:`"+label+"`) REQUIRE (n.namespaceId,n.id) IS UNIQUE").close();
            }
            for(String label:List.of("Namespace","User")) {
                tx.execute("CREATE CONSTRAINT deposit_"+label+"_id IF NOT EXISTS FOR (n:"+label+") REQUIRE n.id IS UNIQUE").close();
            }
            for(String label:List.of("Descriptor","ClassDescriptor")) tx.execute("CREATE CONSTRAINT deposit_"+label+"_name IF NOT EXISTS FOR (n:"+label+") REQUIRE (n.namespaceId,n.ownerId,n.name) IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_class_name IF NOT EXISTS FOR (n:Class) REQUIRE (n.namespaceId,n.name) IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_tool_name IF NOT EXISTS FOR (n:UserTool) REQUIRE (n.namespaceId,n.name) IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_pattern_name IF NOT EXISTS FOR (n:Pattern) REQUIRE (n.namespaceId,n.name) IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_namespace_name IF NOT EXISTS FOR (n:Namespace) REQUIRE n.name IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_username IF NOT EXISTS FOR (n:User) REQUIRE n.username IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_actor_receipt IF NOT EXISTS FOR (n:DepositReceipt) REQUIRE (n.actorId,n.scope,n.operationId) IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_attempt IF NOT EXISTS FOR (n:DepositOperationAttempt) REQUIRE (n.actorId,n.scope,n.operationId) IS UNIQUE").close();
            tx.execute("CREATE INDEX deposit_attempt_deadline IF NOT EXISTS FOR (n:DepositOperationAttempt) ON (n.claimDeadline)").close();
            tx.execute("CREATE INDEX deposit_attempt_writer IF NOT EXISTS FOR (n:DepositOperationAttempt) ON (n.uploadId)").close();
            tx.execute("CREATE INDEX deposit_resource_writer_session IF NOT EXISTS FOR (n:DepositTransfer) ON (n.writerSession)").close();
            tx.execute("CREATE INDEX deposit_exchange_writer_session IF NOT EXISTS FOR (n:DepositExchangeStage) ON (n.writerSession)").close();
            tx.execute("CREATE INDEX deposit_exchange_cleanup IF NOT EXISTS FOR (n:DepositExchangeStage) ON (n.cleanupAfter)").close();
            tx.execute("CREATE INDEX deposit_exchange_state IF NOT EXISTS FOR (n:DepositExchangeStage) ON (n.state)").close();
            tx.execute("CREATE CONSTRAINT deposit_exchange_member IF NOT EXISTS FOR (n:DepositExchangeUploadMember) REQUIRE (n.uploadId,n.name) IS UNIQUE").close();
            tx.execute("CREATE INDEX deposit_exchange_member_writer IF NOT EXISTS FOR (n:DepositExchangeUploadMember) ON (n.uploadId)").close();
            tx.execute("CREATE CONSTRAINT deposit_import_result_id IF NOT EXISTS FOR (n:DepositImportResult) REQUIRE n.id IS UNIQUE").close();
            tx.execute("CREATE INDEX deposit_import_result_expiry IF NOT EXISTS FOR (n:DepositImportResult) ON (n.expiresAt)").close();
            tx.execute("CREATE INDEX deposit_import_result_entry_root IF NOT EXISTS FOR (n:DepositImportResultEntry) ON (n.resultId)").close();
            tx.execute("CREATE CONSTRAINT deposit_import_result_collection_id IF NOT EXISTS FOR (n:DepositImportResultCollection) REQUIRE (n.resultId,n.id) IS UNIQUE").close();
            tx.execute("CREATE INDEX deposit_import_result_collection_name IF NOT EXISTS FOR (n:DepositImportResultCollection) ON (n.resultId,n.name)").close();
            tx.execute("CREATE INDEX deposit_import_result_entry_position IF NOT EXISTS FOR (n:DepositImportResultEntry) ON (n.resultId,n.collectionId,n.position)").close();
            tx.execute("CREATE INDEX deposit_import_result_entry_key IF NOT EXISTS FOR (n:DepositImportResultEntry) ON (n.resultId,n.collectionId,n.mapKeyHash)").close();
            tx.execute("CREATE INDEX deposit_import_result_entry_position_key IF NOT EXISTS FOR (n:DepositImportResultEntry) ON (n.resultId,n.collectionId,n.position,n.mapKeyHash)").close();
            tx.execute("CREATE INDEX deposit_transfer_state IF NOT EXISTS FOR (n:DepositTransfer) ON (n.state)").close();
            tx.execute("CREATE CONSTRAINT deposit_membership_identity IF NOT EXISTS FOR ()-[r:MEMBER_OF]-() REQUIRE r.identityKey IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_relationship_identity IF NOT EXISTS FOR ()-[r:RELATED_TO]-() REQUIRE r.identityKey IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_init IF NOT EXISTS FOR (n:DepositInitialization) REQUIRE n.id IS UNIQUE").close();
            tx.execute("CREATE INDEX deposit_related_identity IF NOT EXISTS FOR ()-[r:RELATED_TO]-() ON (r.namespaceId,r.id)").close();
            tx.execute("CREATE INDEX deposit_objects IF NOT EXISTS FOR (n:Object) ON (n.namespaceId)").close();
            for(String label:List.of("DepositTransfer","DepositExchangeStage","DepositResourceLocation","DepositResourceLease","DepositExportGrant","DepositPruneJob","DepositPruneItem","DepositImportPlan"))tx.execute("CREATE CONSTRAINT "+label+"_id IF NOT EXISTS FOR (n:"+label+") REQUIRE n.id IS UNIQUE").close();
            tx.commit();
        }
        try(var tx=db.beginTx()) {
            OperationAttemptRepository.initialize(tx,null);
            tx.commit();
        }
    }
}
