package deposit.service;

import deposit.common.*;
import deposit.common.Results.Outcome;
import java.util.*;
import java.util.function.Supplier;
import org.neo4j.graphdb.Node;

public abstract class ActionService extends BaseService {
    protected ActionService(RequestContext c) {super(c);}
    protected record Change(Outcome outcome,boolean changed) {}
    protected Outcome action(String procedure,Map<String,Object> request,boolean serverScope,String role,Supplier<Change> work) {
        if(serverScope) authenticate();else scoped(role);
        mutation();
        // Serialize durable actor/operation scope reservation across Namespaces.
        // Dedicated lock nodes avoid cycles with role edges. Always actor before scope.
        repo.lockActor(c.actorId);
        if(serverScope)c.server.lockServer(c.state);
        if(!serverScope) {repo.lockNamespace(c.namespaceId);requireRole(c.namespaceId,role);}
        String scope=serverScope?"server":c.namespaceId;
        String hash=CursorCodec.requestHash(c,Json.write(Json.map("procedure",procedure,"request",request,"expectedRevision",Set.of("deposit.import.analyze","deposit.import.rows","deposit.import.dataByPattern","deposit.import.apply").contains(procedure)?null:c.expectedRevision)));
        Node binding=repo.binding(c.actorId,c.operationId);
        if(binding!=null)OperationalGuards.bindingScope(c,scope,binding.getProperty("scope"));
        Node receipt=repo.receipt(scope,c.operationId);
        if(receipt!=null) {
            c.replayed=true;
            OperationalGuards.receiptActor(c,receipt.getProperty("actorId"));
            OperationalGuards.receiptHash(c,hash,receipt.getProperty("requestHash"));
            c.revision=serverScope?c.server.revision(c.state):((Number)receipt.getProperty("revision")).longValue();
            return repo.replay(receipt,scope);
        }
        c.revision=serverScope?c.server.revision(c.state):repo.sequence(scope);if(c.expectedRevision!=null)checkRevision();
        boolean scientific=!serverScope&&(DomainService.COMMANDS.contains(procedure)||procedure.equals("deposit.apply.batch")||procedure.equals("deposit.import.apply")||Set.of("deposit.utils.undo","deposit.utils.redo","deposit.utils.restore").contains(procedure));
        var history=new deposit.repository.HistoryRepository(c.tx,repo);
        Map<String,Map<String,Object>> beforeState=serverScope?Map.of():history.capture(scope);
        if(scientific&&!"disabled".equals(repo.namespace(scope).getProperty("historyMode")))c.journal=new ChangeSet(beforeState);
        Change change=work.get();
        if(!serverScope){var patterns=new PatternService(c);boolean canonicalized=patterns.canonicalizeResolvedDeclarations();Map<String,Map<String,Object>> resulting=history.capture(scope);patterns.validateNamespace();patterns.validateDeclarationDeletion(beforeState,resulting);if(canonicalized&&!change.changed())change=new Change(change.outcome(),true);}
        if(change.changed()) {
            if(serverScope) {c.state.serverChanged=true;c.revision=c.server.revision(c.state);}
            else {repo.advanceNamespace(scope,c.actorId);c.revision=repo.sequence(scope);}
            if(c.journal!=null){c.journal.checkpoint(history.capture(scope));Revision revision=history.append(scope,c.actorId,c.operationId,c.revision,c.historyKind,procedure,c.journal,c.historyLink,c.historyTarget,c.timestamp);change.outcome().data().put("revisionId",revision.id());}
        } else change.outcome().data().put("changed",false);
        if(scientific){c.server.storagePoint("history-before-receipt");c.state.afterCommit.add(()->c.server.storagePoint("history-after-commit"));}
        repo.saveReceipt(c,scope,hash,change.outcome());return change.outcome();
    }
    protected Outcome outcome(Node node) {return new Outcome(node,null,repo.data(node));}
}
