package deposit.repository;

import java.util.*;
import org.neo4j.graphdb.*;

/** Startup-only schema provisioning, separate from all public actions. */
public final class SchemaRepository {
    private SchemaRepository() {}
    public static void initialize(GraphDatabaseService db) {
        try(var tx=db.beginTx()) {
            for(String label:List.of("Revision","DepositSnapshot","DepositHistoryEpoch"))tx.execute("CREATE CONSTRAINT deposit_"+label+"_identity IF NOT EXISTS FOR (n:"+label+") REQUIRE (n.namespaceId,n.id) IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_revision_tag IF NOT EXISTS FOR (n:RevisionTag) REQUIRE (n.namespaceId,n.revisionId) IS UNIQUE").close();
            tx.execute("CREATE INDEX deposit_revision_sequence IF NOT EXISTS FOR (n:Revision) ON (n.namespaceId,n.sequence)").close();
            tx.execute("CREATE INDEX deposit_revision_epoch_sequence IF NOT EXISTS FOR (n:Revision) ON (n.namespaceId,n.historyEpochId,n.sequence)").close();
            tx.execute("CREATE INDEX deposit_snapshot_epoch IF NOT EXISTS FOR (n:DepositSnapshot) ON (n.namespaceId,n.historyEpochId)").close();
            for(String label:List.of("Class","Object","Descriptor","ClassDescriptor","UserTool","DGeometry","DResource")) {
                tx.execute("CREATE CONSTRAINT deposit_"+label+"_id IF NOT EXISTS FOR (n:`"+label+"`) REQUIRE (n.namespaceId,n.id) IS UNIQUE").close();
            }
            for(String label:List.of("Namespace","User")) {
                tx.execute("CREATE CONSTRAINT deposit_"+label+"_id IF NOT EXISTS FOR (n:"+label+") REQUIRE n.id IS UNIQUE").close();
            }
            for(String label:List.of("Descriptor","ClassDescriptor")) tx.execute("CREATE CONSTRAINT deposit_"+label+"_name IF NOT EXISTS FOR (n:"+label+") REQUIRE (n.namespaceId,n.ownerId,n.name) IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_class_name IF NOT EXISTS FOR (n:Class) REQUIRE (n.namespaceId,n.name) IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_tool_name IF NOT EXISTS FOR (n:UserTool) REQUIRE (n.namespaceId,n.name) IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_namespace_name IF NOT EXISTS FOR (n:Namespace) REQUIRE n.name IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_username IF NOT EXISTS FOR (n:User) REQUIRE n.username IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_receipt IF NOT EXISTS FOR (n:DepositReceipt) REQUIRE (n.scope,n.operationId) IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_binding IF NOT EXISTS FOR (n:DepositOperationBinding) REQUIRE (n.actorId,n.operationId) IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_prepared_action IF NOT EXISTS FOR (n:DepositPreparedAction) REQUIRE (n.actorId,n.recoveryKey) IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_edge IF NOT EXISTS FOR (n:DepositEdgeIdentity) REQUIRE n.key IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_init IF NOT EXISTS FOR (n:DepositInitialization) REQUIRE n.id IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_actor_lock IF NOT EXISTS FOR (n:DepositActorLock) REQUIRE n.id IS UNIQUE").close();
            tx.execute("CREATE INDEX deposit_objects IF NOT EXISTS FOR (n:Object) ON (n.namespaceId)").close();
            for(String label:List.of("DepositStorageLock","DepositNamespaceLock","DepositTransfer","DepositResourceLocation","DepositResourceLease","DepositExportGrant","DepositPruneJob","DepositPruneItem"))tx.execute("CREATE CONSTRAINT "+label+"_id IF NOT EXISTS FOR (n:"+label+") REQUIRE n.id IS UNIQUE").close();
            tx.commit();
        }
        try(var tx=db.beginTx()) {
            if(tx.findNode(Label.label("DepositStorageLock"),"id","spatial")==null)tx.createNode(Label.label("DepositStorageLock")).setProperty("id","spatial");
            try(var nodes=tx.findNodes(Label.label("Namespace"))) {for(Node n:nodes.stream().toList())if(tx.findNode(Label.label("DepositNamespaceLock"),"id",n.getProperty("id"))==null)tx.createNode(Label.label("DepositNamespaceLock")).setProperty("id",n.getProperty("id"));}
            tx.commit();
        }
    }
}
