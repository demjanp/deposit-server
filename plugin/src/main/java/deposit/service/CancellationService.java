package deposit.service;
import deposit.common.*;
import deposit.common.Results.ValueRow;
import java.util.*;

public final class CancellationService extends BaseService {
    public CancellationService(RequestContext c) {super(c);}
    public ValueRow cancel(Map<String,Object> request) {
        fields(request,"requestId");readContext();
        // Cancellation must not wait behind the authorization/scope locks of its target.
        var tx=(org.neo4j.kernel.impl.coreapi.InternalTransaction)c.tx;
        c.server.authenticate(c,tx.securityContext().subject().executingUser(),false);
        var actor=repo.find("User","id",c.actorId);
        if(actor==null||!Boolean.TRUE.equals(actor.getProperty("enabled",false)))c.fail("AUTH_FAILED","Account unavailable");
        boolean administrator="administrator".equals(actor.getProperty("serverRole"));
        boolean requested=c.server.cancel(RequestContext.uuid(request.get("requestId")),c.actorId,administrator);
        c.namespaceId=null;c.revision=c.server.committedRevision();return new ValueRow(requested,c.meta());
    }
}
