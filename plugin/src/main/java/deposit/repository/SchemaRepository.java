package deposit.repository;

import java.util.*;
import org.neo4j.graphdb.*;

/** Startup-only schema provisioning, separate from all public actions. */
public final class SchemaRepository {
    private SchemaRepository() {}
    public static void initialize(GraphDatabaseService db) {
        try(var tx=db.beginTx()) {
            for(String label:List.of("Revision","DepositSnapshot","DepositHistoryEpoch"))tx.execute("CREATE CONSTRAINT deposit_"+label+"_identity IF NOT EXISTS FOR (n:"+label+") REQUIRE (n.namespaceId,n.id) IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_snapshot_entity IF NOT EXISTS FOR (n:DepositSnapshot) REQUIRE (n.namespaceId,n.revisionId,n.entityKind,n.entityId) IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_revision_dependency IF NOT EXISTS FOR (n:RevisionDependency) REQUIRE (n.namespaceId,n.revisionId,n.entityKey) IS UNIQUE").close();
            tx.execute("CREATE INDEX deposit_revision_dependency_epoch IF NOT EXISTS FOR (n:RevisionDependency) ON (n.namespaceId,n.historyEpochId)").close();
            tx.execute("CREATE INDEX deposit_revision_sequence IF NOT EXISTS FOR (n:Revision) ON (n.namespaceId,n.sequence)").close();
            tx.execute("CREATE INDEX deposit_revision_epoch_sequence IF NOT EXISTS FOR (n:Revision) ON (n.namespaceId,n.historyEpochId,n.sequence)").close();
            tx.execute("CREATE INDEX deposit_revision_epoch IF NOT EXISTS FOR (n:Revision) ON (n.namespaceId,n.historyEpochId)").close();
            tx.execute("CREATE INDEX deposit_expired_history_epoch IF NOT EXISTS FOR (n:DepositHistoryEpoch) ON (n.namespaceId,n.expired,n.startedSequence)").close();
            tx.execute("CREATE INDEX deposit_snapshot_epoch IF NOT EXISTS FOR (n:DepositSnapshot) ON (n.namespaceId,n.historyEpochId)").close();
            for(String label:List.of("Class","Object","Descriptor","ClassDescriptor","Pattern","UserTool","DGeometry","DResource")) {
                tx.execute("CREATE CONSTRAINT deposit_"+label+"_id IF NOT EXISTS FOR (n:`"+label+"`) REQUIRE (n.namespaceId,n.id) IS UNIQUE").close();
            }
            tx.execute("CREATE CONSTRAINT deposit_resource_identity IF NOT EXISTS FOR (n:DResource) REQUIRE n.id IS UNIQUE").close();
            tx.execute("CREATE INDEX deposit_resource_orphan_age IF NOT EXISTS FOR (n:DResource) ON (n.orphanSince)").close();
            for(String label:List.of("Namespace","User")) {
                tx.execute("CREATE CONSTRAINT deposit_"+label+"_id IF NOT EXISTS FOR (n:"+label+") REQUIRE n.id IS UNIQUE").close();
            }
            for(String label:List.of("Descriptor","ClassDescriptor")) tx.execute("CREATE CONSTRAINT deposit_"+label+"_name IF NOT EXISTS FOR (n:"+label+") REQUIRE (n.namespaceId,n.ownerId,n.name) IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_class_name IF NOT EXISTS FOR (n:Class) REQUIRE (n.namespaceId,n.name) IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_tool_name IF NOT EXISTS FOR (n:UserTool) REQUIRE (n.namespaceId,n.name) IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_pattern_name IF NOT EXISTS FOR (n:Pattern) REQUIRE (n.namespaceId,n.name) IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_namespace_name IF NOT EXISTS FOR (n:Namespace) REQUIRE n.name IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_username IF NOT EXISTS FOR (n:User) REQUIRE n.username IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_membership_identity IF NOT EXISTS FOR ()-[r:MEMBER_OF]-() REQUIRE r.identityKey IS UNIQUE").close();
            tx.execute("CREATE CONSTRAINT deposit_relationship_identity IF NOT EXISTS FOR ()-[r:RELATED_TO]-() REQUIRE r.identityKey IS UNIQUE").close();
            tx.execute("CREATE INDEX deposit_related_identity IF NOT EXISTS FOR ()-[r:RELATED_TO]-() ON (r.namespaceId,r.id)").close();
            tx.execute("CREATE INDEX deposit_objects IF NOT EXISTS FOR (n:Object) ON (n.namespaceId)").close();
            tx.commit();
        }
    }
}
