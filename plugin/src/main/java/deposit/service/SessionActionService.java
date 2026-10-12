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
    public SessionActionService(RequestContext c){super(c);prepared=new PreparedActionRepository(c.tx);}
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
            c.actionScope=scope(procedure,arguments);c.operational=operational(procedure);
            c.operationId=UUID.randomUUID().toString();c.expectedRevision=c.operational?null:baseline(c.actionScope);
            c.request=new ArgumentService(c,new LinkedHashMap<>(),false).request(procedure,arguments);
            if(Set.of("deposit.save.data","deposit.get.data","deposit.get.namespaceData","deposit.save.csv","deposit.save.xlsx","deposit.save.gpkg").contains(procedure))authorize(procedure,c.actionScope,exportRole(procedure,arguments));
            if(procedure.equals("deposit.delete.namespace"))c.request.put("expectedNamespaceRevision",baseline(active()));
        }
        synchronized(c.session){c.session.lastOperation=c.operationId;c.session.lastScope=c.actionScope;}
    }
    private String fingerprint(String procedure,List<Object> arguments){return CursorCodec.requestHash(c,Json.write(Json.map("procedure",procedure,"arguments",arguments)));}
    private String active(){String ns=c.state.scoped?c.state.namespace:c.session.namespace;if(ns==null)c.fail("NO_ACTIVE_NAMESPACE","Select a Namespace first");return ns;}
    private String scope(String procedure,List<Object> arguments){
        String scope=ProcedureCatalog.spec(procedure).scope();
        if(scope.equals("prune"))return "all".equals(ValueCodec.map(arguments.getFirst()).get("scope"))?"server":active();
        return scope.equals("server")?"server":active();
    }
    private boolean operational(String procedure){return ProcedureCatalog.spec(procedure).scope().equals("operational")||procedure.equals("deposit.admin.pruneResources");}
    private String exportRole(String procedure,List<Object> arguments){
        Object options=null;
        if(procedure.equals("deposit.save.data")||procedure.equals("deposit.get.data"))options=arguments!=null&&arguments.size()>1?arguments.get(1):null;
        else if(procedure.equals("deposit.get.namespaceData"))options=arguments!=null&&!arguments.isEmpty()?arguments.get(0):null;
        else if(procedure.equals("deposit.save.csv")||procedure.equals("deposit.save.xlsx")||procedure.equals("deposit.save.gpkg"))return "viewer";
        return options instanceof Map<?,?> m&&Boolean.TRUE.equals(ValueCodec.map(m).get("includeHistory"))?"owner":"viewer";
    }
    private long baseline(String scope){
        Long value=c.state.observations.get(scope);if(value!=null)return value;
        c.fail("REVISION_CONFLICT","No observed revision; refresh this scope before writing");return -1;
    }
    private void lease(){
        synchronized(c.session){
            if(c.session.mutationOwner!=null&&c.session.mutationOwner!=c.state)c.fail("SESSION_BUSY","Another mutation is active for this session");
            if(c.session.mutationOwner==c.state)return;
            c.session.mutationOwner=c.state;
            c.state.release.add(()->{synchronized(c.session){if(c.session.mutationOwner==c.state)c.session.mutationOwner=null;}});
        }
    }
    private void separate(){
        if(c.state.scoped||c.state.mutation||c.state.selection)c.fail("VALIDATION_FAILED","Session/action controls require a separate transaction");
        lease();c.state.selection=true;
    }
    private void authorize(String procedure,String scope){authorize(procedure,scope,null);}
    private void authorize(String procedure,String scope,String roleOverride){
        if(procedure.equals("deposit.admin.pruneResources")||Set.of("deposit.create.user","deposit.set.user").contains(procedure))admin(repo.find("User","id",c.actorId));
        if(!scope.equals("server")){
            if(!scope.equals(active()))c.fail("SCOPE_MISMATCH","Action belongs to another Namespace");
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
        if(c.procedure.equals("deposit.delete.namespace")&&!deleted){String target=(String)action.getProperty("namespaceId");if(!target.equals(active()))c.fail("SCOPE_MISMATCH","Deletion target changed");requireRole(target,"owner");}
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
            case "deposit.action.prepare"->Stream.of(new DataRow(prepare(request),c.meta()));
            case "deposit.action.status","deposit.action.resume"->Stream.of(new DataRow(status(request,procedure.endsWith("resume")),c.meta()));
            case "deposit.action.release"->{separate();c.state.afterCommit.add(()->c.session.preparedAction=null);yield Stream.of(new ValueRow(true,c.meta()));}
            case "deposit.utils.refresh"->Stream.of(new DataRow(refresh(string(request.get("scope"))),c.meta()));
            case "deposit.get.session"->Stream.of(new DataRow(session(),c.meta()));
            case "deposit.get.lastOperation"->Stream.of(new DataRow(lastOperation(),c.meta()));
            case "deposit.utils.toData"->Stream.of(new DataRow(toData(request.get("entity")),c.meta()));
            case "deposit.get.requests"->c.server.activeRequests(c.actorId,"administrator".equals(repo.find("User","id",c.actorId).getProperty("serverRole")),c.state).stream().map(r->new DataRow(r,c.meta()));
            case "deposit.utils.cancel"->Stream.of(new ValueRow(c.server.cancelSession(c.state.tokenHash,c.state),c.meta()));
            default->throw new IllegalArgumentException();
        };
    }
    private Map<String,Object> lastOperation(){
        String id=c.session.lastOperation,scope=c.session.lastScope;Node receipt=id==null?null:repo.receipt(scope,id);
        if(scope!=null&&!scope.equals("server"))requireRole(scope,"viewer");
        return Json.map("operationId",id,"scope",scope,"state",id==null?"none":receipt==null?"unresolved":"committed",
            "revision",receipt==null?null:receipt.getProperty("revision"),"outcome",receipt==null?null:Json.read((String)receipt.getProperty("outcome")));
    }
    private Map<String,Object> prepare(Map<String,Object> request){
        separate();String key=RequestContext.uuid(request.get("recoveryKey")),procedure=string(request.get("procedure"));var spec=ProcedureCatalog.spec(procedure);
        if(!spec.action())c.fail("VALIDATION_FAILED","Only mutations can be prepared");
        if(!(request.get("arguments") instanceof List<?>))c.fail("VALIDATION_FAILED","arguments must be a list");
        @SuppressWarnings("unchecked") List<Object> args=(List<Object>)request.get("arguments");
        if(args.size()!=spec.bindings().size())c.fail("VALIDATION_FAILED","Wrong number of domain arguments");
        if(procedure.equals("deposit.admin.pruneResources")&&!Boolean.FALSE.equals(ValueCodec.map(args.getFirst()).get("dryRun")))c.fail("VALIDATION_FAILED","Dry runs cannot be prepared");
        String hash=fingerprint(procedure,args);repo.lockActor(c.actorId);Node action=prepared.find(c.actorId,key);
        if(action!=null){prepared.lock(action);if(!hash.equals(action.getProperty("inputHash")))c.fail("OPERATION_MISMATCH","Recovery key input changed");checkAccess(action);}
        else{
            String scope=scope(procedure,args);String authorizationRole=Set.of("deposit.save.data","deposit.get.data","deposit.get.namespaceData","deposit.save.csv","deposit.save.xlsx","deposit.save.gpkg").contains(procedure)?exportRole(procedure,args):null;authorize(procedure,scope,authorizationRole);var bindings=new LinkedHashMap<String,Object>();new ArgumentService(c,bindings,false).request(procedure,args);
            var properties=Json.map("operationId",UUID.randomUUID().toString(),"procedure",procedure,"scope",scope,"inputHash",hash,"bindings",Json.write(bindings),"runId",c.server.runId,"operational",operational(procedure));
            if(authorizationRole!=null)properties.put("authorizationRole",authorizationRole);
            if(!operational(procedure))properties.put("expectedRevision",baseline(scope));
            if(procedure.equals("deposit.delete.namespace")){requireRole(active(),"owner");properties.put("namespaceId",active());properties.put("namespaceRevision",baseline(active()));}
            action=prepared.create(c.actorId,key,properties);
        }
        attach(key);return prepared.state(action,repo);
    }
    private void attach(String key){
        if(c.session.preparedAction!=null&&!key.equals(c.session.preparedAction))c.fail("SESSION_BUSY","Release the attached action first");
        c.state.afterCommit.add(()->c.session.preparedAction=key);
    }
    private void checkAccess(Node action){
        String scope=(String)action.getProperty("scope"),procedure=(String)action.getProperty("procedure");Node receipt=repo.receipt(scope,(String)action.getProperty("operationId"));
        if(procedure.equals("deposit.delete.namespace")&&receipt!=null)return;
        authorize(procedure,scope,action.hasProperty("authorizationRole")?(String)action.getProperty("authorizationRole"):null);
        if(procedure.equals("deposit.delete.namespace")){String target=(String)action.getProperty("namespaceId");if(!target.equals(active()))c.fail("SCOPE_MISMATCH","Deletion target changed");requireRole(target,"owner");}
    }
    private Map<String,Object> status(Map<String,Object> request,boolean resume){
        if(resume)separate();String key=RequestContext.uuid(request.get("recoveryKey"));repo.lockActor(c.actorId);Node action=prepared.find(c.actorId,key);
        if(action==null)c.fail("NOT_FOUND","Prepared action not found");prepared.lock(action);checkAccess(action);if(resume)attach(key);return prepared.state(action,repo);
    }
    private Map<String,Object> refresh(String scope){
        if(!Set.of("namespace","server","both").contains(scope))c.fail("VALIDATION_FAILED","Invalid refresh scope");separate();
        if(c.session.preparedAction!=null)c.fail("SESSION_BUSY","Release prepared action before refresh");
        var values=new LinkedHashMap<String,Long>();if(!scope.equals("namespace"))values.put("server",c.server.revision(c.state));
        if(!scope.equals("server")){String ns=active();requireRole(ns,"viewer");values.put(ns,repo.sequence(ns));}
        c.state.afterCommit.add(()->{synchronized(c.session){c.session.baselines.putAll(values);c.session.observationGeneration++;}});
        return Json.map("scope",scope,"observed",values);
    }
    private Map<String,Object> session(){
        Map<String,Long> observations;synchronized(c.session){observations=Map.copyOf(c.session.baselines);}
        String ns=c.session.namespace;String role=ns==null?null:effectiveRole(ns);
        return Json.map("user",repo.data(repo.find("User","id",c.actorId)),"namespaceId",ns,"role",role,"administrativeMode",administrativeMode(),"expiresAt",java.time.Instant.ofEpochMilli(Math.min(c.session.created+28_800_000L,c.session.touched+1_800_000L)).toString(),"observed",observations,"preparedAction",c.session.preparedAction,"serverRevision",c.server.revision(c.state));
    }
    private Map<String,Object> toData(Object entity){
        if(entity instanceof Node n){
            String kind=CoreRepository.kind(n);if(!Set.of("Namespace","Class","Object","Descriptor","ClassDescriptor","Pattern","UserTool","DGeometry","DResource").contains(kind))c.fail("VALIDATION_FAILED","Expected a public Deposit entity");
            String ns=kind.equals("Namespace")?CoreRepository.id(n):(String)n.getProperty("namespaceId",null);requireRole(ns,"viewer");
            if(!kind.equals("Namespace"))scoped("viewer");
            if(!kind.equals("Namespace")&&!ns.equals(active()))c.fail("SCOPE_MISMATCH","Entity belongs to another Namespace");c.namespaceId=ns;c.revision=repo.sequence(ns);
            var data=repo.data(n);if(kind.equals("Namespace"))data.put("role",effectiveRole(ns));return data;
        }
        if(entity instanceof Relationship rel){scoped("viewer");String kind=CoreRepository.kind(rel);if(!Set.of("Membership","Relationship","ClassRelationship").contains(kind))c.fail("VALIDATION_FAILED","Expected a public Deposit relationship");String ns=(String)rel.getStartNode().getProperty("namespaceId",null);if(!Objects.equals(ns,active()))c.fail("SCOPE_MISMATCH","Relationship belongs to another Namespace");requireRole(ns,"viewer");c.namespaceId=ns;c.revision=repo.sequence(ns);return repo.data(rel);}
        c.fail("VALIDATION_FAILED","Expected a native Deposit node or relationship");return null;
    }
}
