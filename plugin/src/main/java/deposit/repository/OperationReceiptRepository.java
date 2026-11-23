package deposit.repository;

import deposit.common.ImportResultContract;
import deposit.common.RequestContext;
import deposit.common.Results.Outcome;
import java.util.*;
import org.neo4j.graphdb.*;

/** Minimal receipt persistence in the enclosing action transaction. */
final class OperationReceiptRepository {
    interface Access {
        Node find(String label, Map<String,Object> properties);
    }

    private final Transaction tx;
    private final Access access;

    OperationReceiptRepository(Transaction tx, Access access) {
        this.tx = Objects.requireNonNull(tx);
        this.access = Objects.requireNonNull(access);
    }

    Node receipt(String scope, String operation) {
        return access.find("DepositReceipt", Map.of("scope", scope, "operationId", operation));
    }

    Node receipt(String actor, String scope, String operation) {
        return access.find("DepositReceipt", Map.of("actorId", actor, "scope", scope, "operationId", operation));
    }

    void saveReceipt(RequestContext context, String scope, String hash, Outcome outcome) {
        Node receipt = tx.createNode(Label.label("DepositReceipt"));
        receipt.setProperty("scope", scope);
        receipt.setProperty("operationId", context.operationId);
        receipt.setProperty("actorId", context.actorId);
        receipt.setProperty("requestHash", hash);
        Object format = outcome.data().get("format"), resultId = outcome.data().get("resultId");
        if (ImportResultContract.FORMAT.equals(format) && resultId instanceof String id) {
            receipt.setProperty("importResultId", id);
            receipt.setProperty("resultKind",outcome.data().get("kind"));
            if (outcome.data().get("namespaceId") instanceof String namespace) receipt.setProperty("resultNamespaceId", namespace);
        }
        receipt.setProperty("revision", context.revision);
        receipt.setProperty("administrativeMode", context.state.scoped ? context.state.administrative : (context.session != null && context.session.administrative));
    }
}
