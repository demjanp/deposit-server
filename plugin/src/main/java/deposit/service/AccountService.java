package deposit.service;

import deposit.common.*;
import deposit.common.Results.*;
import deposit.repository.CoreRepository;
import java.util.*;
import java.time.*;
import org.neo4j.graphdb.Node;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;

public final class AccountService extends ActionService {
    public AccountService(RequestContext c) {super(c);}
    public DataRow bootstrap(Map<String,Object> request) {
        fields(request,"bootstrapSecret","username","password");
        String secret=string(request.get("bootstrapSecret")),username=name(request.get("username")),password=AccountCredentials.password(request.get("password"));
        if(c.state.mutation||c.state.selection||c.state.scoped)c.fail("VALIDATION_FAILED","Bootstrap must be the transaction's only action");c.state.mutation=true;
        c.server.guard(c.state,false);
        c.server.lockServer(c.state);
        if(c.server.revision(c.state)!=0||repo.bootstrapped()||!c.server.acceptsBootstrap(secret))c.fail("AUTH_FAILED","Bootstrap unavailable or secret invalid");
        Node user=repo.create("User",null,null,Json.map("username",username,"passwordHash",AccountCredentials.hash(password),"enabled",true,"serverRole","administrator"));
        repo.markBootstrap();c.state.serverChanged=true;c.state.afterCommit.add(c.server::consumeBootstrap);c.revision=c.server.revision(c.state);
        return new DataRow(repo.data(user),c.meta());
    }
    public LoginRow login(Map<String,Object> request) {
        c.server.guard(c.state,false);
        fields(request,"username","password");String username=name(request.get("username")),password=string(request.get("password"));
        var internal=(InternalTransaction)c.tx;
        String origin=internal.clientInfo().clientAddress();
        // Connection origin excludes the ephemeral source port, including bracketed IPv6.
        if(origin!=null) {int bracket=origin.indexOf(']');int colon=origin.lastIndexOf(':');if(bracket>=0)origin=origin.substring(0,bracket+1);else if(colon>=0)origin=origin.substring(0,colon);}
        c.server.checkLoginDelay(username,origin,c.state.kernel);
        Node user=repo.find("User","username",username);
        boolean valid=AccountCredentials.validLogin(user,password);
        if(!valid) {c.server.loginFailed(username,origin);c.fail("AUTH_FAILED","Invalid credentials");}
        String token=ServerContext.randomToken();var session=new ServerContext.Session(CoreRepository.id(user),internal.securityContext().subject().executingUser(),c.server.sessionTime());
        c.state.afterCommit.add(()->c.server.registerSession(Json.hash(token),session));c.revision=c.server.revision(c.state);
        session.baselines.put("server",c.revision);
        return new LoginRow(token,Json.map("user",repo.data(user),"expiresAt",Instant.ofEpochMilli(session.created+1_800_000).toString()),c.meta());
    }
    /** Browser credentials share the same hash and throttle policy but create a transport-bound session. */
    public String loginBrowser(String username,String password,String origin) {
        c.server.guard(c.state,false);
        username=name(username);password=string(password);
        c.server.checkLoginDelay(username,origin,c.state.kernel);
        Node user=repo.find("User","username",username);
        boolean valid=AccountCredentials.validLogin(user,password);
        if(!valid){c.server.loginFailed(username,origin);c.fail("AUTH_FAILED","Invalid credentials");}
        String token=ServerContext.randomToken();var session=new ServerContext.Session(CoreRepository.id(user),null,c.server.sessionTime(),ServerContext.Transport.BROWSER);
        session.csrfToken=ServerContext.randomToken();c.state.afterCommit.add(()->c.server.registerSession(Json.hash(token),session));
        c.revision=c.server.revision(c.state);return token;
    }
    public ValueRow logout(Map<String,Object> request) {
        fields(request);readContext();authenticate();
        if(c.state.scoped||c.state.mutation)c.fail("VALIDATION_FAILED","Logout requires separate transaction");
        c.state.selection=true;c.state.afterCommit.add(()->c.server.removeSession(Json.hash(c.token)));c.revision=c.server.revision(c.state);return new ValueRow(true,c.meta());
    }
    public Outcome createUser(Map<String,Object> request) { return new AccountMutationService(c).createUser(request); }
    public Outcome createNamespace(Map<String,Object> request) { return new NamespaceAdministrationService(c).create(request); }
    public Outcome setUser(Map<String,Object> request) { return new AccountMutationService(c).setUser(request); }
    public ValueRow setPassword(Map<String,Object> request) { return new AccountMutationService(c).setPassword(request); }
    public ValueRow administrative(Map<String,Object> request,boolean set) {
        fields(request,set?new String[]{"enabled"}:new String[]{});readContext();Node user=authenticate();
        boolean enabled=c.state.scoped?c.state.administrative:c.session.administrative;
        if(set) {
            if(c.state.scoped||c.state.mutation)c.fail("VALIDATION_FAILED","Mode changes require separate transaction");
            if(!(request.get("enabled") instanceof Boolean))c.fail("VALIDATION_FAILED","enabled must be Boolean");
            enabled=(Boolean)request.get("enabled");if(enabled)admin(user);
            boolean committed=enabled;var session=c.session;c.state.selection=true;
            c.state.afterCommit.add(()->session.administrative=committed);
        }
        c.revision=c.server.revision(c.state);return new ValueRow(enabled,c.meta());
    }
    public Outcome setNamespace(Map<String,Object> request) { return new NamespaceAdministrationService(c).setNamespace(request); }
    public Outcome deleteNamespace(Map<String,Object> request) { return new NamespaceAdministrationService(c).delete(request); }
    public EntityRow active(Map<String,Object> request,boolean set) { return new NamespaceAdministrationService(c).active(request,set); }
    public Outcome setRole(Map<String,Object> request) { return new NamespaceAdministrationService(c).setRole(request); }
    public DataRow operation(Map<String,Object> request) {
        fields(request,"operationId","scope");readContext();Node user=authenticate();String scope=string(request.getOrDefault("scope","namespace"));
        if(scope.equals("namespace")) {scoped("viewer");scope=c.namespaceId;}
        else if(scope.equals("server"))c.revision=c.server.revision(c.state);
        else c.fail("VALIDATION_FAILED","Invalid receipt scope");
        String operation=RequestContext.uuid(request.get("operationId"));Node binding=repo.binding(c.actorId,operation);
        if(binding!=null&&!scope.equals(binding.getProperty("scope")))c.fail("SCOPE_MISMATCH","Operation belongs to another scope");
        Node receipt=repo.receipt(scope,operation);
        if(receipt==null)c.fail("NOT_FOUND","Operation not found");
        if(!c.actorId.equals(receipt.getProperty("actorId"))) {
            if(scope.equals("server"))admin(user);else requireRole(c.namespaceId,"owner");
        }
        return new DataRow(Json.map("operationId",operation,"state","committed","outcome",Json.read((String)receipt.getProperty("outcome"))),c.meta());
    }
}
