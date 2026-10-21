package deposit.service;

import deposit.common.*;
import deposit.common.Results.*;
import deposit.repository.*;
import java.util.*;
import java.util.stream.Stream;
import org.neo4j.graphdb.*;

/** Session observations and explicit recoverable actions; ordinary calls always get fresh identities. */
public final class SessionActionService extends BaseService {
    private final PreparedActionRepository prepared;
    final SessionActionScope scopeRules;
    private final SessionDiagnostics diagnostics;
    private final PreparedActionWorkflow preparedActions;
    public SessionActionService(RequestContext c){super(c);prepared=new PreparedActionRepository(c.tx);scopeRules=new SessionActionScope(c);diagnostics=new SessionDiagnostics(c);preparedActions=new PreparedActionWorkflow(c,prepared,this);}
    private boolean cancellation(String p){return Set.of("deposit.utils.cancel","deposit.utils.cancelRequest","deposit.get.requests").contains(p);}
    public void begin(String procedure,List<Object> arguments){
        c.publicCall=true;c.procedure=procedure;
        var spec=ProcedureCatalog.spec(procedure);
        boolean action=spec.action();
        if(procedure.equals("deposit.admin.pruneResources"))action=Boolean.FALSE.equals(ValueCodec.map(arguments.getFirst()).get("dryRun"));
        if(Set.of("deposit.utils.login","deposit.utils.bootstrap").contains(procedure)){
            c.request=new ArgumentService(c,new LinkedHashMap<>(),false).request(procedure,arguments);return;
        }
        if(cancellation(procedure)){
            var tx=(org.neo4j.kernel.impl.coreapi.InternalTransaction)c.tx;c.server.authenticate(c,tx.securityContext().subject().executingUser(),false);
            Node actor=repo.find("User","id",c.actorId);if(actor==null||!Boolean.TRUE.equals(actor.getProperty("enabled",false)))c.fail("AUTH_FAILED","Account unavailable");
        }else {
            // Claim the nonblocking session lease before waiting for authorization fences.
            var tx=(org.neo4j.kernel.impl.coreapi.InternalTransaction)c.tx;
            c.server.authenticate(c,tx.securityContext().subject().executingUser(),false);
            if(action||Set.of("deposit.action.prepare","deposit.action.resume","deposit.action.release","deposit.utils.refresh","deposit.set.activeNamespace","deposit.set.administrativeMode","deposit.utils.logout").contains(procedure))lease();
            if(Set.of("deposit.set.user","deposit.set.password","deposit.set.namespaceRole","deposit.delete.namespace").contains(procedure))authorizationChange();
            authenticate();
        }
        synchronized(c.session){
            if(!c.state.observationsCaptured){c.state.observations.putAll(c.session.baselines);c.state.observationGeneration=c.session.observationGeneration;c.state.observationsCaptured=true;}
            c.observationGeneration=c.state.observationGeneration;
        }
        if(Set.of("namespace","operational").contains(spec.scope()))scoped("viewer");
        if(Set.of("deposit.set.activeNamespace","deposit.set.administrativeMode","deposit.utils.logout").contains(procedure))lease();

        if(!action){c.request=new ArgumentService(c,new LinkedHashMap<>(),false).request(procedure,arguments);return;}
        if(c.state.mutation)c.fail("VALIDATION_FAILED","Only one mutation is allowed per transaction; use batch");
        lease();
        c.inputHash=fingerprint(procedure,arguments);
        String attached=c.session.preparedAction;
        if(attached!=null){
            repo.lockActor(c.actorId);Node actionNode=prepared.find(c.actorId,attached);if(actionNode==null)c.fail("NOT_FOUND","Prepared action unavailable");prepared.lock(actionNode);
            if(!procedure.equals(actionNode.getProperty("procedure"))||!c.inputHash.equals(actionNode.getProperty("inputHash")))c.fail("OPERATION_MISMATCH","Prepared action arguments differ");
            restore(actionNode);
            c.request=new ArgumentService(c,Json.read((String)actionNode.getProperty("bindings")),true).request(procedure,arguments);
            if(procedure.equals("deposit.delete.namespace"))c.request.put("expectedNamespaceRevision",actionNode.getProperty("namespaceRevision"));
        }else{
            c.actionScope=scopeRules.scope(procedure,arguments);c.operational=scopeRules.operational(procedure);
            c.operationId=UUID.randomUUID().toString();c.expectedRevision=c.operational?null:scopeRules.baseline(c.actionScope);
            c.request=new ArgumentService(c,new LinkedHashMap<>(),false).request(procedure,arguments);
            if(Set.of("deposit.save.data","deposit.get.data","deposit.get.namespaceData","deposit.save.csv","deposit.save.xlsx","deposit.save.gpkg").contains(procedure))authorize(procedure,c.actionScope,scopeRules.exportRole(procedure,arguments));
            if(procedure.equals("deposit.delete.namespace"))c.request.put("expectedNamespaceRevision",scopeRules.baseline(scopeRules.active()));
        }
        synchronized(c.session){c.session.lastOperation=c.operationId;c.session.lastScope=c.actionScope;}
    }
    String fingerprint(String procedure,List<Object> arguments){return CursorCodec.requestHash(c,Json.write(Json.map("procedure",procedure,"arguments",arguments)));}
    private void lease(){
        synchronized(c.session){
            Object identity=c.state.identityToken();
            if(c.session.mutationOwner!=null&&c.session.mutationOwner!=identity)c.fail("SESSION_BUSY","Another mutation is active for this session");
            if(c.session.mutationOwner==identity)return;
            c.session.mutationOwner=identity;
            c.state.release.add(()->{synchronized(c.session){if(c.session.mutationOwner==identity)c.session.mutationOwner=null;}});
        }
    }
    void separate(){
        if(c.state.scoped||c.state.mutation||c.state.selection)c.fail("VALIDATION_FAILED","Session/action controls require a separate transaction");
        lease();c.state.selection=true;
    }
    private void authorize(String procedure,String scope){authorize(procedure,scope,null);}
    void authorize(String procedure,String scope,String roleOverride){
        if(procedure.equals("deposit.admin.pruneResources")||Set.of("deposit.create.user","deposit.set.user").contains(procedure))admin(repo.find("User","id",c.actorId));
        if(!scope.equals("server")){
            if(!scope.equals(scopeRules.active()))c.fail("SCOPE_MISMATCH","Action belongs to another Namespace");
            String role=roleOverride!=null?roleOverride:Set.of("deposit.set.namespace","deposit.delete.all","deposit.set.namespaceRole","deposit.set.versioningType","deposit.set.versionTag","deposit.utils.restore").contains(procedure)?"owner":"editor";
            requireRole(scope,role);
        }
    }
    private void restore(Node action){
        c.actionScope=(String)action.getProperty("scope");c.operationId=(String)action.getProperty("operationId");c.operational=(boolean)action.getProperty("operational");
        c.expectedRevision=c.operational?null:((Number)action.getProperty("expectedRevision")).longValue();
        Node receipt=repo.receipt(c.actionScope,c.operationId);
        boolean deleted=c.procedure.equals("deposit.delete.namespace")&&receipt!=null;
        if(!deleted)authorize(c.procedure,c.actionScope,action.hasProperty("authorizationRole")?(String)action.getProperty("authorizationRole"):null);
        if(c.procedure.equals("deposit.delete.namespace")&&!deleted){String target=(String)action.getProperty("namespaceId");if(!target.equals(scopeRules.active()))c.fail("SCOPE_MISMATCH","Deletion target changed");requireRole(target,"owner");}
        if(receipt==null&&c.actionScope.equals("server")&&!c.server.runId.equals(action.getProperty("runId")))c.fail("REVISION_CONFLICT","Server restarted; refresh and prepare a new action");
    }
    public void observe(){
        if(c.session==null||cancellation(c.procedure)||c.procedure.startsWith("deposit.action.")||Set.of("deposit.get.session","deposit.get.lastOperation","deposit.utils.refresh").contains(c.procedure))return;
        String scope=c.actionScope!=null?c.actionScope:c.namespaceId==null?"server":c.namespaceId;
        long revision=c.revision,generation=c.observationGeneration;
        boolean committedAction=c.operationId!=null&&!c.operational&&!c.replayed;
        c.state.afterCommit.add(()->{synchronized(c.session){
            if(committedAction){c.session.baselines.put(scope,revision);c.session.observationGeneration++;}
            else if(c.session.observationGeneration==generation)c.session.baselines.putIfAbsent(scope,revision);
        }});
    }
    public Stream<?> control(String procedure,Map<String,Object> request){
        return switch(procedure){
            case "deposit.action.prepare"->Stream.of(new DataRow(preparedActions.prepare(request),c.meta()));
            case "deposit.action.status","deposit.action.resume"->Stream.of(new DataRow(preparedActions.status(request,procedure.endsWith("resume")),c.meta()));
            case "deposit.action.release"->{separate();c.state.afterCommit.add(()->c.session.preparedAction=null);yield Stream.of(new ValueRow(true,c.meta()));}
            case "deposit.utils.refresh"->Stream.of(new DataRow(refresh(string(request.get("scope"))),c.meta()));
            case "deposit.get.session"->Stream.of(new DataRow(diagnostics.session(),c.meta()));
            case "deposit.get.lastOperation"->Stream.of(new DataRow(diagnostics.lastOperation(),c.meta()));
            case "deposit.utils.toData"->Stream.of(new DataRow(diagnostics.toData(request.get("entity")),c.meta()));
            case "deposit.get.requests"->c.server.activeRequests(c.actorId,"administrator".equals(repo.find("User","id",c.actorId).getProperty("serverRole")),c.state).stream().map(r->new DataRow(r,c.meta()));
            case "deposit.utils.cancel"->Stream.of(new ValueRow(c.server.cancelSession(c.state.tokenHash,c.state),c.meta()));
            default->throw new IllegalArgumentException();
        };
    }
    private Map<String,Object> refresh(String scope){
        if(!Set.of("namespace","server","both").contains(scope))c.fail("VALIDATION_FAILED","Invalid refresh scope");separate();
        if(c.session.preparedAction!=null)c.fail("SESSION_BUSY","Release prepared action before refresh");
        var values=new LinkedHashMap<String,Long>();if(!scope.equals("namespace"))values.put("server",c.server.revision(c.state));
        if(!scope.equals("server")){String ns=scopeRules.active();requireRole(ns,"viewer");values.put(ns,repo.sequence(ns));}
        c.state.afterCommit.add(()->{synchronized(c.session){c.session.baselines.putAll(values);c.session.observationGeneration++;}});
        return Json.map("scope",scope,"observed",values);
    }

}
