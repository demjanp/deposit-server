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
        String hash=CursorCodec.requestHash(c,Json.write(Json.map("procedure",procedure,"request",request,"expectedRevision",c.expectedRevision)));
        Node binding=repo.binding(c.actorId,c.operationId);
        if(binding!=null && !scope.equals(binding.getProperty("scope"))) c.fail("SCOPE_MISMATCH","Operation belongs to another scope");
        Node receipt=repo.receipt(scope,c.operationId);
        if(receipt!=null) {
            if(!c.actorId.equals(receipt.getProperty("actorId")))c.fail("FORBIDDEN","Operation belongs to another actor");
            if(!hash.equals(receipt.getProperty("requestHash")))c.fail("OPERATION_MISMATCH","Operation input changed");
            c.revision=serverScope?c.server.revision(c.state):((Number)receipt.getProperty("revision")).longValue();
            return repo.replay(receipt,scope);
        }
        c.revision=serverScope?c.server.revision(c.state):repo.sequence(scope);checkRevision();
        Change change=work.get();
        if(change.changed()) {
            if(serverScope) {c.state.serverChanged=true;c.revision=c.server.revision(c.state);}
            else {repo.advanceNamespace(scope,c.actorId);c.revision=repo.sequence(scope);}
        } else change.outcome().data().put("changed",false);
        repo.saveReceipt(c,scope,hash,change.outcome());return change.outcome();
    }
    protected Outcome outcome(Node node) {return new Outcome(node,null,repo.data(node));}
}
