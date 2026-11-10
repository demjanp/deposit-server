package deposit.repository;

import deposit.common.Json;
import deposit.common.RequestContext;
import deposit.common.Results.Outcome;
import java.util.*;
import org.neo4j.graphdb.*;

/** Receipt and operation-binding persistence in the enclosing action transaction. */
final class OperationReceiptRepository {
    interface Access {
        Node find(String label, Map<String,Object> properties);
        Node entity(String label, String namespace, String id);
        Relationship membership(Node object);
        Relationship relationship(String namespace, String category, String id);
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

    Node binding(String actor, String operation) {
        return access.find("DepositOperationBinding", Map.of("actorId", actor, "operationId", operation));
    }

    void saveReceipt(RequestContext context, String scope, String hash, Outcome outcome) {
        Node receipt = tx.createNode(Label.label("DepositReceipt"));
        receipt.setProperty("scope", scope);
        receipt.setProperty("operationId", context.operationId);
        receipt.setProperty("actorId", context.actorId);
        receipt.setProperty("requestHash", hash);
        receipt.setProperty("outcome", Json.write(outcome.data()));
        Object format = outcome.data().get("format"), resultId = outcome.data().get("resultId");
        if ("deposit.import-result/1".equals(format) && resultId instanceof String id)
            receipt.setProperty("importResultId", id);
        receipt.setProperty("revision", context.revision);
        receipt.setProperty("administrativeMode", context.state.scoped ? context.state.administrative : (context.session != null && context.session.administrative));
        if (outcome.node() != null) {
            receipt.setProperty("nodeKind", CoreRepository.kind(outcome.node()));
            receipt.setProperty("entityId", CoreRepository.id(outcome.node()));
        }
        if (outcome.rel() != null) {
            receipt.setProperty("relId", CoreRepository.id(outcome.rel()));
            receipt.setProperty("relKind", CoreRepository.kind(outcome.rel()));
            receipt.setProperty("objectId", CoreRepository.id(outcome.rel().getStartNode()));
        }
        Node binding = tx.createNode(Label.label("DepositOperationBinding"));
        binding.setProperty("actorId", context.actorId);
        binding.setProperty("operationId", context.operationId);
        binding.setProperty("scope", scope);
    }

    Outcome replay(Node savedReceipt, String scope) {
        Node node = null;
        Relationship relationship = null;
        if (savedReceipt.hasProperty("entityId")) {
            String label = (String)savedReceipt.getProperty("nodeKind");
            String id = (String)savedReceipt.getProperty("entityId");
            node = Set.of("Namespace", "User").contains(label) ? access.find(label, Map.of("id", id)) : access.entity(label, scope, id);
        }
        if (savedReceipt.hasProperty("relId")) {
            String category = (String)savedReceipt.getProperty("relKind", "Membership");
            if (category.equals("Membership")) {
                Node object = access.entity("Object", scope, (String)savedReceipt.getProperty("objectId"));
                if (object != null) {
                    Relationship candidate = access.membership(object);
                    if (candidate != null && savedReceipt.getProperty("relId").equals(CoreRepository.id(candidate))) relationship = candidate;
                }
            } else relationship = access.relationship(scope, category, (String)savedReceipt.getProperty("relId"));
        }
        return new Outcome(node, relationship, Json.read((String)savedReceipt.getProperty("outcome")));
    }
}
