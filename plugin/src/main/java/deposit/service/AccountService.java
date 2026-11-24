package deposit.service;

import deposit.common.*;
import deposit.common.Results.*;
import deposit.repository.CoreRepository;
import java.util.*;
import java.time.*;
import org.neo4j.graphdb.Node;
import org.neo4j.internal.kernel.api.security.AuthSubject;
import org.neo4j.internal.kernel.api.security.AuthenticationResult;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;

public final class AccountService extends ActionService {
    public AccountService(RequestContext c) {super(c);}
    public DataRow bootstrap(Map<String,Object> request) {
        var security=((InternalTransaction)c.tx).securityContext();var subject=security.subject();
        if(security.isAuthDisabled()||subject==AuthSubject.AUTH_DISABLED||subject==AuthSubject.ANONYMOUS||subject.getAuthenticationResult()!=AuthenticationResult.SUCCESS)
            c.fail("AUTH_FAILED","Bootstrap requires an authenticated Neo4j administrator");
        fields(request,"username","password");
        String username=name(request.get("username")),password=AccountCredentials.password(request.get("password"));
        if(c.state.mutation||c.state.selection||c.state.scoped)c.fail("VALIDATION_FAILED","Bootstrap must be the transaction's only action");c.state.mutation=true;
        c.server.guard(c.state,false);
        c.server.lockServer(c.state);
        var bootstrap = c.server.storage.operationalState.bootstrap();
        if (bootstrap.initialized() || bootstrap.pendingUser() != null || repo.hasUsers())
            c.fail("AUTH_FAILED","Bootstrap unavailable");
        String userId = UUID.randomUUID().toString();
        var properties = Json.map("username",username,"passwordHash",AccountCredentials.hash(password),"enabled",true,"serverRole","administrator");
        c.server.beginBootstrap(c.state, userId);
        Node user = repo.createRestored("User", null, null, properties, userId);
        c.state.serverChanged=true;c.revision=c.server.revision(c.state);
        return new DataRow(repo.data(user),c.meta());
    }
    public LoginRow login(Map<String,Object> request) {
        c.server.guard(c.state,false);
        requireSettledBootstrap();
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
        requireSettledBootstrap();
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
        fields(request);readContext();
        if(c.state.scoped||c.state.mutation)c.fail("VALIDATION_FAILED","Logout requires separate transaction");
        c.state.selection=true;c.state.afterCommit.add(()->c.server.removeSession(Json.hash(c.token)));c.revision=c.server.committedRevision();return new ValueRow(true,c.meta());
    }
    public Outcome createUser(Map<String,Object> request) { return new AccountMutationService(c).createUser(request); }
    private void requireSettledBootstrap() {
        if (c.server.storage.operationalState.bootstrap().pendingUser() != null)
            c.fail("OUTCOME_UNKNOWN", "Bootstrap settlement is pending");
    }
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
}
