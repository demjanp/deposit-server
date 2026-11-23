package deposit.service;

import deposit.common.*;
import deposit.common.Results.*;
import deposit.repository.*;
import java.util.*;
import java.util.stream.Stream;
import org.neo4j.graphdb.*;

/** Session observations and single-attempt action dispatch. */
public final class SessionActionService extends BaseService {
    final SessionActionScope scopeRules;
    private final SessionDiagnostics diagnostics;
    private static final Set<String> OPERATION_CONTROLS=Set.of("deposit.action.begin","deposit.action.cancel","deposit.get.operation");
    private boolean enforceAttempt;
    public SessionActionService(RequestContext c){super(c);scopeRules=new SessionActionScope(c);diagnostics=new SessionDiagnostics(c);}
    private boolean cancellation(String p){return Set.of("deposit.utils.cancel","deposit.utils.cancelRequest","deposit.get.requests","deposit.utils.logout").contains(p);}
    public void beginPublic(String procedure,List<Object> arguments,String operationId){
        if(c.state.operationClaimed)c.fail("VALIDATION_FAILED","An operation must execute in a separate transaction");
        enforceAttempt=true;
        c.operationId=operationId;
        c.uploadContinuation=OperationAttemptService.uploadContinuation(procedure,arguments);
        begin(procedure,arguments);
    }
    public void begin(String procedure,List<Object> arguments){
        c.publicCall=true;c.procedure=procedure;
        var spec=ProcedureCatalog.spec(procedure);
        boolean action=spec.action();
        if(procedure.equals("deposit.admin.pruneResources"))action=Boolean.FALSE.equals(ValueCodec.map(arguments.getFirst()).get("dryRun"));
        if(Set.of("deposit.utils.login","deposit.utils.bootstrap").contains(procedure)){
            c.request=new ArgumentService(c,new LinkedHashMap<>(),false).request(procedure,arguments);return;
        }
        if(cancellation(procedure)||OPERATION_CONTROLS.contains(procedure)){
            var tx=(org.neo4j.kernel.impl.coreapi.InternalTransaction)c.tx;c.server.authenticate(c,tx.securityContext().subject().executingUser(),false);
            Node actor=repo.find("User","id",c.actorId);if(actor==null||!Boolean.TRUE.equals(actor.getProperty("enabled",false)))c.fail("AUTH_FAILED","Account unavailable");
        }else {
            // Claim the nonblocking session lease before waiting for authorization fences.
            var tx=(org.neo4j.kernel.impl.coreapi.InternalTransaction)c.tx;
            c.server.authenticate(c,tx.securityContext().subject().executingUser(),false);
            if(action||Set.of("deposit.utils.refresh","deposit.set.activeNamespace","deposit.set.administrativeMode","deposit.utils.logout").contains(procedure))lease();
            if(Set.of("deposit.set.user","deposit.set.password","deposit.set.namespaceRole","deposit.delete.namespace").contains(procedure))authorizationChange();
            authenticate();
        }
        synchronized(c.session){
            if(!c.state.observationsCaptured){c.state.observations.putAll(c.session.baselines);c.state.observationGeneration=c.session.observationGeneration;c.state.observationsCaptured=true;}
            c.observationGeneration=c.state.observationGeneration;
        }
        if(Set.of("namespace","operational").contains(spec.scope()))scoped("viewer");
        if(Set.of("deposit.set.activeNamespace","deposit.set.administrativeMode").contains(procedure))lease();

        if(!action){
            if(enforceAttempt&&c.operationId!=null)c.fail("VALIDATION_FAILED","Procedure does not accept an operation ID");
            c.request=new ArgumentService(c,new LinkedHashMap<>(),false).request(procedure,arguments);return;
        }
        if(c.state.mutation||c.state.selection)c.fail("VALIDATION_FAILED","Only one mutation is allowed per transaction; use batch");
        lease();
        if(enforceAttempt){
            new OperationAttemptService(c).claim(procedure,arguments,scopeRules.scope(procedure,arguments));
            synchronized(c.session){c.session.lastOperation=c.operationId;c.session.lastScope=c.actionScope;}
            return;
        }
        c.inputHash=fingerprint(procedure,arguments);
        c.actionScope=scopeRules.scope(procedure,arguments);c.operational=scopeRules.operational(procedure);
        if(c.operationId==null)c.operationId=UUID.randomUUID().toString();c.expectedRevision=c.operational?null:scopeRules.baseline(c.actionScope);
        c.request=new ArgumentService(c,new LinkedHashMap<>(),false).request(procedure,arguments);
        if(Set.of("deposit.get.data","deposit.get.namespaceData","deposit.exchange.export","deposit.save.csv","deposit.save.xlsx","deposit.save.gpkg").contains(procedure))authorize(procedure,c.actionScope,scopeRules.exportRole(procedure,arguments));
        if(procedure.equals("deposit.delete.namespace"))c.request.put("expectedNamespaceRevision",scopeRules.baseline(scopeRules.active()));
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
    public void observe(){
        if(c.session==null||cancellation(c.procedure)||OPERATION_CONTROLS.contains(c.procedure)||c.procedure.startsWith("deposit.action.")||Set.of("deposit.get.session","deposit.get.lastOperation","deposit.utils.refresh").contains(c.procedure))return;
        String scope=c.actionScope!=null?c.actionScope:c.namespaceId==null?"server":c.namespaceId;
        long revision=c.revision,generation=c.observationGeneration;
        boolean committedAction=c.operationId!=null&&!c.operational;
        boolean usesCurrentServerRevision=scope.equals("server")&&!"deposit.import.cloneNamespace".equals(c.procedure);
        c.state.afterCommit.add(()->{synchronized(c.session){
            if(committedAction){c.session.baselines.put(scope,usesCurrentServerRevision?c.server.committedRevision():revision);c.session.observationGeneration++;}
            else if(c.session.observationGeneration==generation)c.session.baselines.putIfAbsent(scope,revision);
        }});
    }
    public Stream<?> finish(Stream<?> result){
        if(!c.state.operationClaimed)return result;
        try(result){
            var rows=result.iterator();
            if(!rows.hasNext())throw new IllegalStateException("Tracked procedure did not return a result");
            Object row=rows.next();
            if(rows.hasNext())throw new IllegalStateException("Tracked procedure returned more than one result");
            new OperationAttemptService(c).finish(row);
            return Stream.of(row);
        }
    }
    public Stream<?> control(String procedure,Map<String,Object> request){
        return switch(procedure){
            case "deposit.action.begin"->Stream.of(new DataRow(new OperationAttemptService(c).begin(request),c.meta()));
            case "deposit.get.operation","deposit.action.cancel"->Stream.of(new DataRow(new OperationAttemptService(c).inspect(request,procedure.endsWith("cancel")),c.meta()));
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
        var values=new LinkedHashMap<String,Long>();if(!scope.equals("namespace"))values.put("server",c.server.revision(c.state));
        if(!scope.equals("server")){String ns=scopeRules.active();requireRole(ns,"viewer");values.put(ns,repo.sequence(ns));}
        c.state.afterCommit.add(()->{synchronized(c.session){c.session.baselines.putAll(values);c.session.observationGeneration++;}});
        return Json.map("scope",scope,"observed",values);
    }

}
