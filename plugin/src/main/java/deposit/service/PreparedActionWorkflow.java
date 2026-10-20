package deposit.service;

import deposit.common.*;
import deposit.repository.PreparedActionRepository;
import java.util.*;
import org.neo4j.graphdb.Node;

/** Durable preparation, authorization recheck, attachment and status projection. */
final class PreparedActionWorkflow extends BaseService {
    private final PreparedActionRepository prepared;
    private final SessionActionService owner;
    PreparedActionWorkflow(RequestContext c,PreparedActionRepository prepared,SessionActionService owner) {
        super(c);this.prepared=prepared;this.owner=owner;
    }
    Map<String,Object> prepare(Map<String,Object> request) {
        owner.separate();String key=RequestContext.uuid(request.get("recoveryKey")),procedure=string(request.get("procedure"));var spec=ProcedureCatalog.spec(procedure);
        if(!spec.action())c.fail("VALIDATION_FAILED","Only mutations can be prepared");
        if(!(request.get("arguments") instanceof List<?>))c.fail("VALIDATION_FAILED","arguments must be a list");
        @SuppressWarnings("unchecked") List<Object> args=(List<Object>)request.get("arguments");
        if(args.size()!=spec.bindings().size())c.fail("VALIDATION_FAILED","Wrong number of domain arguments");
        if(procedure.equals("deposit.admin.pruneResources")&&!Boolean.FALSE.equals(ValueCodec.map(args.getFirst()).get("dryRun")))c.fail("VALIDATION_FAILED","Dry runs cannot be prepared");
        String hash=owner.fingerprint(procedure,args);repo.lockActor(c.actorId);Node action=prepared.find(c.actorId,key);
        if(action!=null) {
            prepared.lock(action);
            if(!hash.equals(action.getProperty("inputHash")))c.fail("OPERATION_MISMATCH","Recovery key input changed");
            checkAccess(action);
        } else {
            String scope=owner.scopeRules.scope(procedure,args);
            String authorizationRole=Set.of("deposit.save.data","deposit.get.data","deposit.get.namespaceData","deposit.save.csv","deposit.save.xlsx","deposit.save.gpkg").contains(procedure)?owner.scopeRules.exportRole(procedure,args):null;
            owner.authorize(procedure,scope,authorizationRole);
            var bindings=new LinkedHashMap<String,Object>();new ArgumentService(c,bindings,false).request(procedure,args);
            var properties=Json.map("operationId",UUID.randomUUID().toString(),"procedure",procedure,"scope",scope,"inputHash",hash,"bindings",Json.write(bindings),"runId",c.server.runId,"operational",owner.scopeRules.operational(procedure));
            if(authorizationRole!=null)properties.put("authorizationRole",authorizationRole);
            if(!owner.scopeRules.operational(procedure))properties.put("expectedRevision",owner.scopeRules.baseline(scope));
            if(procedure.equals("deposit.delete.namespace")) {
                requireRole(owner.scopeRules.active(),"owner");
                properties.put("namespaceId",owner.scopeRules.active());
                properties.put("namespaceRevision",owner.scopeRules.baseline(owner.scopeRules.active()));
            }
            action=prepared.create(c.actorId,key,properties);
        }
        attach(key);return prepared.state(action,repo);
    }
    Map<String,Object> status(Map<String,Object> request,boolean resume) {
        if(resume)owner.separate();String key=RequestContext.uuid(request.get("recoveryKey"));repo.lockActor(c.actorId);Node action=prepared.find(c.actorId,key);
        if(action==null)c.fail("NOT_FOUND","Prepared action not found");
        prepared.lock(action);checkAccess(action);if(resume)attach(key);return prepared.state(action,repo);
    }
    void checkAccess(Node action) {
        String scope=(String)action.getProperty("scope"),procedure=(String)action.getProperty("procedure");
        Node receipt=repo.receipt(scope,(String)action.getProperty("operationId"));
        if(procedure.equals("deposit.delete.namespace")&&receipt!=null)return;
        owner.authorize(procedure,scope,action.hasProperty("authorizationRole")?(String)action.getProperty("authorizationRole"):null);
        if(procedure.equals("deposit.delete.namespace")) {
            String target=(String)action.getProperty("namespaceId");
            if(!target.equals(owner.scopeRules.active()))c.fail("SCOPE_MISMATCH","Deletion target changed");
            requireRole(target,"owner");
        }
    }
    private void attach(String key) {
        if(c.session.preparedAction!=null&&!key.equals(c.session.preparedAction))c.fail("SESSION_BUSY","Release the attached action first");
        c.state.afterCommit.add(()->c.session.preparedAction=key);
    }
}
