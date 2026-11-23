package deposit.service;

import deposit.common.*;
import deposit.common.Results.*;
import deposit.repository.CoreRepository;
import java.util.*;
import org.neo4j.graphdb.Node;

/** Namespace creation, deletion, selection and role administration. */
final class NamespaceAdministrationService extends ActionService {
    NamespaceAdministrationService(RequestContext c) { super(c); }
    Outcome create(Map<String,Object> request) {
        fields(request,"name","description","historyMode");
        return action("deposit.create.namespace",request,true,null,()->{
            String name=name(request.get("name")),mode=string(request.getOrDefault("historyMode","persistent"));
            if(!Set.of("disabled","transient","persistent").contains(mode))c.fail("VALIDATION_FAILED","Invalid historyMode");
            if(repo.find("Namespace","name",name)!=null)c.fail("DUPLICATE","Namespace name exists");
            Node ns=repo.create("Namespace",null,c.actorId,Json.map("name",name,"description",string(request.getOrDefault("description","")),"historyMode",mode,"historyEpochId",UUID.randomUUID().toString(),"sequence",0L));
            repo.setRole(c.actorId,CoreRepository.id(ns),"owner");new deposit.repository.HistoryRepository(c.tx,repo).baseline(CoreRepository.id(ns),c.actorId,0,c.server.runId,c.timestamp);
            var data=repo.data(ns);data.put("role","owner");return new Change(new Outcome(ns,null,data),true);
        });
    }
    Outcome setNamespace(Map<String,Object> request) { return new DomainService(c).execute("deposit.set.namespace",request); }
    Outcome delete(Map<String,Object> request) {
        fields(request,"confirmNamespaceName","expectedNamespaceRevision");authorizationChange();authenticate();mutation();
        repo.lockActor(c.actorId);c.server.lockServer(c.state);
        scoped("owner");String target=c.namespaceId;repo.lockNamespace(target);requireRole(target,"owner");
        long sequence=repo.sequence(target);c.revision=c.server.revision(c.state);checkRevision();
        if(integer(request.get("expectedNamespaceRevision"))!=sequence)c.fail("REVISION_CONFLICT","Namespace revision changed");
        if(!string(request.get("confirmNamespaceName")).equals(repo.namespace(target).getProperty("name")))c.fail("VALIDATION_FAILED","Namespace name confirmation changed");
        String hash=deletionHash(request,target);List<String> deleted=repo.deleteNamespace(target);
        c.namespaceId=null;c.state.serverChanged=true;c.revision=c.server.revision(c.state);
        Outcome result=new Outcome(null,null,Json.map("deletedIds",deleted));repo.saveReceipt(c,"server",hash,result);
        repo.receipt(c,"server").setProperty("deletedNamespaceId",target);
        c.state.afterCommit.add(()->c.server.clearNamespace(target));return result;
    }
    private String deletionHash(Map<String,Object> request,String target) {
        return c.state.operationClaimed?c.inputHash:CursorCodec.requestHash(c,Json.write(Json.map("procedure","deposit.delete.namespace","request",request,"targetNamespaceId",target,"expectedRevision",c.expectedRevision)));
    }
    EntityRow active(Map<String,Object> request,boolean set) {
        fields(request,set?new String[]{"name","id"}:new String[]{});readContext();authenticate();
        String namespace=c.state.scoped?c.state.namespace:c.session.namespace;
        if(set) {
            if(c.state.scoped||c.state.mutation)c.fail("VALIDATION_FAILED","Selection requires separate transaction");
            if(request.size()!=1)c.fail("VALIDATION_FAILED","Supply exactly one Namespace selector");
            Node selected=request.containsKey("name")?repo.find("Namespace","name",name(request.get("name"))):repo.find("Namespace","id",RequestContext.uuid(request.get("id")));
            if(selected==null)c.fail("NOT_FOUND","Namespace not found");namespace=CoreRepository.id(selected);requireRole(namespace,"viewer");
            String committedNamespace=namespace;var session=c.session;c.state.selection=true;c.state.afterCommit.add(()->session.namespace=committedNamespace);
        }
        if(namespace==null) {var meta=c.meta();meta.put("revision",null);return new EntityRow(null,null,meta);}
        requireRole(namespace,"viewer");c.namespaceId=namespace;c.revision=repo.sequence(namespace);Node ns=repo.namespace(namespace);var data=repo.data(ns);data.put("role",effectiveRole(namespace));return new EntityRow(ns,data,c.meta());
    }
    Outcome setRole(Map<String,Object> request) {
        fields(request,"userId","role");authorizationChange();
        return action("deposit.set.namespaceRole",request,false,"owner",()->{
            String userId=RequestContext.uuid(request.get("userId"));
            if(!request.containsKey("role"))c.fail("VALIDATION_FAILED","role required");
            String role=request.get("role")==null?null:string(request.get("role"));
            if(role!=null&&rank(role)==0)c.fail("VALIDATION_FAILED","Invalid role");
            Node user=repo.find("User","id",userId);if(user==null)c.fail("NOT_FOUND","User not found");
            if("owner".equals(role)&&!Boolean.TRUE.equals(user.getProperty("enabled")))c.fail("VALIDATION_FAILED","Owner must be enabled");
            if("owner".equals(repo.role(userId,c.namespaceId))&&!"owner".equals(role)) {
                boolean other=repo.roles(c.namespaceId).stream().anyMatch(r->"owner".equals(r.get("role"))&&!userId.equals(r.get("userId"))&&Boolean.TRUE.equals(repo.find("User","id",r.get("userId")).getProperty("enabled")));
                if(!other)c.fail("VALIDATION_FAILED","Namespace requires an enabled owner");
            }
            boolean changed=repo.setRole(userId,c.namespaceId,role);return new Change(new Outcome(null,null,Json.map("userId",userId,"role",role)),changed);
        });
    }
}
