package deposit.service;

import deposit.common.*;
import deposit.repository.CoreRepository;
import java.util.*;
import java.text.Normalizer;
import org.neo4j.graphdb.*;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;

public abstract class BaseService {
    protected final RequestContext c;
    protected final CoreRepository repo;
    protected BaseService(RequestContext context) {c=context;repo=new CoreRepository(context);}
    public static void fields(Map<String,Object> request,String... fields) {
        if(request==null)throw ExceptionHandler.fail("VALIDATION_FAILED","request must be a map");
        Set<String> allowed=Set.of(fields);
        for(String field:request.keySet()) if(!allowed.contains(field)) throw ExceptionHandler.fail("VALIDATION_FAILED","Unknown request field: "+field);
    }
    public static String string(Object value) {return StrictValues.string(value);}
    public static String name(Object value) {
        String s=Normalizer.normalize(string(value).strip(),Normalizer.Form.NFC);
        if(s.isEmpty()||s.codePointCount(0,s.length())>255||s.codePoints().anyMatch(Character::isISOControl)) throw ExceptionHandler.fail("VALIDATION_FAILED","Invalid name");return s;
    }
    public static long integer(Object value) {return StrictValues.integer(value);}
    public static List<Double> position(Object value) {
        if(!(value instanceof List<?> list)||list.size()!=2) throw ExceptionHandler.fail("VALIDATION_FAILED","Position requires two finite numbers");
        var out=new ArrayList<Double>();for(Object v:list) {if(!(v instanceof Number n)||!Double.isFinite(n.doubleValue())) throw ExceptionHandler.fail("VALIDATION_FAILED","Position requires two finite numbers");out.add(n.doubleValue());}return out;
    }
    protected void bindBoltSession() {
        var internal=(InternalTransaction)c.tx;
        c.server.authenticate(c,internal.securityContext().subject().executingUser(),false);
    }
    protected Node authenticate() {
        if(!c.protectedAuthorization)c.server.guard(c.state,false);
        bindBoltSession();
        c.protectedAuthorization=true;
        return requireActor();
    }
    protected Node enabledActor(boolean reuse) {
        if(reuse&&c.state.protectedActor!=null)return c.state.protectedActor;
        Node user=repo.find("User","id",c.actorId);
        if(user==null||!Boolean.TRUE.equals(user.getProperty("enabled",false)))return null;
        if(reuse)c.state.protectedActor=user;
        return user;
    }
    private Node requireActor() {
        Node user=enabledActor(c.protectedAuthorization);
        if(user==null)c.fail("AUTH_FAILED","Account unavailable");
        return user;
    }
    protected Node authenticateBrowser() {
        c.server.authenticateBrowser(c);
        Node user=repo.find("User","id",c.actorId);
        if(user==null||!Boolean.TRUE.equals(user.getProperty("enabled",false)))c.fail("AUTH_FAILED","Account unavailable");
        return user;
    }
    protected void scoped(String minimum) {c.revision=CoreRepository.sequence(scope(minimum));}
    protected Node scope(String minimum) {
        authenticate();
        if(c.state.selection) c.fail("VALIDATION_FAILED","Selection and scoped work need separate transactions");
        if(!c.state.scoped) {c.state.namespace=c.session.namespace;c.state.administrative=c.session.administrative;c.state.scoped=true;}
        c.namespaceId=c.state.namespace;
        if(c.namespaceId==null) c.fail("NO_ACTIVE_NAMESPACE","Select a Namespace first");
        return requireRole(c.namespaceId,minimum);
    }
    protected Node requireRole(String namespace,String minimum) {
        Node ns=repo.namespace(namespace);
        if(ns==null)c.fail("NOT_FOUND","Namespace not found");
        String role=effectiveRole(namespace);
        if(role==null)c.fail("NOT_FOUND","Namespace not found");
        if(rank(role)<rank(minimum)) c.fail("FORBIDDEN","Insufficient Namespace role");
        return ns;
    }
    protected Node required(String kind,Object value) {
        String id=RequestContext.uuid(value);
        String namespace=kind.equals("User")||kind.equals("Namespace")?null:c.namespaceId;
        Node node=findResolved(kind,namespace,id);
        if(node==null)c.fail("NOT_FOUND",kind+" not found");
        c.remember(kind,namespace,id,node);
        return node;
    }
    protected Node findResolved(String kind,String namespace,String id) {
        Node node=c.resolved(kind,namespace,id);
        if(node!=null)return node;
        return switch(kind) {
            case "User" -> repo.find("User","id",id);
            case "Namespace" -> repo.namespace(id);
            default -> repo.entity(kind,namespace,id);
        };
    }
    protected String effectiveRole(String namespace) {
        return AuthorizationAdapter.role(c,repo,requireActor(),namespace,c.protectedAuthorization);
    }
    protected boolean administrativeMode() {return AuthorizationAdapter.mode(c,requireActor());}
    protected void authorizationChange() {
        long generation=c.state.authorizationGeneration;
        c.server.guard(c.state,true);
        if(generation!=c.state.authorizationGeneration)c.clearResolved();
    }
    protected void admin(Node user) {if(!"administrator".equals(user.getProperty("serverRole"))) c.fail("FORBIDDEN","Administrator required");c.state.privileged=true;}
    public static int rank(String role) {return switch(role){case "viewer"->1;case "editor"->2;case "owner"->3;default->0;};}
    protected void readContext() {if(c.operationId!=null||c.expectedRevision!=null) c.fail("VALIDATION_FAILED","Read/session controls do not accept mutation context");}
    protected void mutation() {
        if(c.operationId==null||c.expectedRevision==null) c.fail("VALIDATION_FAILED","operationId and expectedRevision required");
        if(c.state.mutation) c.fail("VALIDATION_FAILED","Only one mutation is allowed per transaction; use batch for grouped actions");
        if(c.state.selection) c.fail("VALIDATION_FAILED","Selection and mutations need separate transactions");
        c.state.mutation=true;
    }
    protected void checkRevision() {if(c.expectedRevision!=c.revision) throw ExceptionHandler.error("REVISION_CONFLICT","Revision changed",c,false,Map.of("currentRevision",c.revision));}
}
