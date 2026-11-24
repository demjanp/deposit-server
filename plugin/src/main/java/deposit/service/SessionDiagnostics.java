package deposit.service;

import deposit.common.*;
import deposit.repository.CoreRepository;
import java.util.*;
import org.neo4j.graphdb.*;

/** Sanitized session, operation and native-entity projections. */
final class SessionDiagnostics extends BaseService {
    SessionDiagnostics(RequestContext c) { super(c); }
    Map<String,Object> lastOperation() {
        String id=c.session.lastOperation,scope=c.session.lastScope;
        if(id==null)return Json.map("operationId",null,"scope",null,"state","none","cleanupState","none");
        return new OperationAttemptService(c).inspect(Json.map("operationId",id,"scope",scope),false);
    }
    Map<String,Object> session() {
        Map<String,Long> observations;
        synchronized(c.session){
            // When session inspection precedes registration, both use the same captured baseline.
            SessionActionService.captureObservations(c);
            observations=Map.copyOf(c.session.baselines);
        }
        String ns=c.session.namespace;String role=ns==null?null:effectiveRole(ns);
        return Json.map("user",repo.data(authenticate()),"namespaceId",ns,"role",role,"administrativeMode",administrativeMode(),
            "expiresAt",java.time.Instant.ofEpochMilli(Math.min(c.session.created+28_800_000L,c.session.touched+1_800_000L)).toString(),
            "observed",observations,"serverRevision",c.server.revision(c.state));
    }
    Map<String,Object> toData(Object entity) {
        if(entity instanceof Node n) {
            String kind=CoreRepository.kind(n);
            if(!Set.of("Namespace","Class","Object","Descriptor","ClassDescriptor","Pattern","UserTool","DGeometry","DResource").contains(kind))c.fail("VALIDATION_FAILED","Expected a public Deposit entity");
            String ns=kind.equals("Namespace")?CoreRepository.id(n):(String)n.getProperty("namespaceId",null);
            Node namespace=requireRole(ns,"viewer");
            if(!kind.equals("Namespace")) {
                if(!ns.equals(activeScope())) {
                    requireRole(c.namespaceId,"viewer");
                    c.fail("SCOPE_MISMATCH","Entity belongs to another Namespace");
                }
            }
            c.namespaceId=ns;c.revision=CoreRepository.sequence(namespace);var data=repo.data(n);
            if(kind.equals("Namespace"))data.put("role",effectiveRole(ns));
            checkProjection(namespace);
            return data;
        }
        if(entity instanceof Relationship rel) {
            Node namespace=scope("viewer");String kind=CoreRepository.kind(rel);
            if(!Set.of("Membership","Relationship","ClassRelationship").contains(kind))c.fail("VALIDATION_FAILED","Expected a public Deposit relationship");
            String ns=(String)rel.getStartNode().getProperty("namespaceId",null),active=c.state.scoped?c.state.namespace:c.session.namespace;
            if(!Objects.equals(ns,active))c.fail("SCOPE_MISMATCH","Relationship belongs to another Namespace");
            c.revision=CoreRepository.sequence(namespace);var data=repo.data(rel);
            checkProjection(namespace);
            return data;
        }
        c.fail("VALIDATION_FAILED","Expected a native Deposit node or relationship");return null;
    }
    private void checkProjection(Node namespace) {
        bindBoltSession();
        if(CoreRepository.sequence(namespace)!=c.revision)c.fail("REVISION_CONFLICT","Revision changed during serialization");
    }
}
