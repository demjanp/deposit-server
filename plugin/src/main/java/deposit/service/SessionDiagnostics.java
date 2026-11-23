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
        if(id!=null&&java.util.UUID.fromString(id).version()==7)
            return new OperationAttemptService(c).inspect(Json.map("operationId",id,"scope",scope),false);
        Node receipt=id==null?null:repo.receipt(c.actorId,scope,id);
        if(scope!=null&&!scope.equals("server"))requireRole(scope,"viewer");
        return Json.map("operationId",id,"scope",scope,"state",id==null?"none":receipt==null?"unresolved":"committed",
            "revision",receipt==null?null:receipt.getProperty("revision"),"outcome",receipt==null?null:Json.read((String)receipt.getProperty("outcome")));
    }
    Map<String,Object> session() {
        Map<String,Long> observations;synchronized(c.session){observations=Map.copyOf(c.session.baselines);}
        String ns=c.session.namespace;String role=ns==null?null:effectiveRole(ns);
        return Json.map("user",repo.data(repo.find("User","id",c.actorId)),"namespaceId",ns,"role",role,"administrativeMode",administrativeMode(),
            "expiresAt",java.time.Instant.ofEpochMilli(Math.min(c.session.created+28_800_000L,c.session.touched+1_800_000L)).toString(),
            "observed",observations,"preparedAction",c.session.preparedAction,"serverRevision",c.server.revision(c.state));
    }
    Map<String,Object> toData(Object entity) {
        if(entity instanceof Node n) {
            String kind=CoreRepository.kind(n);
            if(!Set.of("Namespace","Class","Object","Descriptor","ClassDescriptor","Pattern","UserTool","DGeometry","DResource").contains(kind))c.fail("VALIDATION_FAILED","Expected a public Deposit entity");
            String ns=kind.equals("Namespace")?CoreRepository.id(n):(String)n.getProperty("namespaceId",null);requireRole(ns,"viewer");
            if(!kind.equals("Namespace"))scoped("viewer");
            String active=c.state.scoped?c.state.namespace:c.session.namespace;
            if(!kind.equals("Namespace")&&!ns.equals(active))c.fail("SCOPE_MISMATCH","Entity belongs to another Namespace");
            c.namespaceId=ns;c.revision=repo.sequence(ns);var data=repo.data(n);
            if(kind.equals("Namespace"))data.put("role",effectiveRole(ns));return data;
        }
        if(entity instanceof Relationship rel) {
            scoped("viewer");String kind=CoreRepository.kind(rel);
            if(!Set.of("Membership","Relationship","ClassRelationship").contains(kind))c.fail("VALIDATION_FAILED","Expected a public Deposit relationship");
            String ns=(String)rel.getStartNode().getProperty("namespaceId",null),active=c.state.scoped?c.state.namespace:c.session.namespace;
            if(!Objects.equals(ns,active))c.fail("SCOPE_MISMATCH","Relationship belongs to another Namespace");
            requireRole(ns,"viewer");c.namespaceId=ns;c.revision=repo.sequence(ns);return repo.data(rel);
        }
        c.fail("VALIDATION_FAILED","Expected a native Deposit node or relationship");return null;
    }
}
