package deposit.repository;

import java.util.*;
import org.neo4j.graphdb.*;

/** Bounded history-record deletion using the caller's existing transaction. */
final class HistoryRecordDeletion {
    private static final List<String> NAMESPACE_RECORDS = List.of("DepositSnapshot", "RevisionTag", "RevisionTouch", "RevisionDependency", "Revision", "DepositHistoryEpoch");
    private final Transaction tx;

    HistoryRecordDeletion(Transaction tx) { this.tx = Objects.requireNonNull(tx); }

    void deleteNamespace(String namespace) {
        for (String label : NAMESPACE_RECORDS) {
            try (ResourceIterator<Node> nodes = tx.findNodes(Label.label(label), "namespaceId", namespace)) {
                while(nodes.hasNext())delete(nodes.next());
            }
        }
    }

    static void delete(Node node) {
        try (ResourceIterable<Relationship> relationships = node.getRelationships()) {
            for (Relationship relationship : relationships) relationship.delete();
        }
        node.delete();
    }
}
