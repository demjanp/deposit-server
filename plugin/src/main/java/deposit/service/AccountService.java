package deposit.service;

import deposit.common.*;
import deposit.common.Results.*;
import deposit.repository.CoreRepository;
import java.util.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.bouncycastle.crypto.generators.OpenBSDBCrypt;
import org.neo4j.graphdb.Node;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;

public final class AccountService extends ActionService {
    public AccountService(RequestContext c) {super(c);}
    private static String password(Object value) {
        String p=string(value);if(p.codePointCount(0,p.length())<12||p.getBytes(StandardCharsets.UTF_8).length>72) throw ExceptionHandler.fail("VALIDATION_FAILED","Password requires at least 12 characters and at most 72 UTF-8 bytes");return p;
    }
    private static String hashPassword(String password) {
        char[] chars=password.toCharArray();
        try {return OpenBSDBCrypt.generate("2y",chars,ServerContext.randomBytes(16),12);}
        finally {Arrays.fill(chars,'\0');}
    }
    public DataRow bootstrap(Map<String,Object> request) {
        fields(request,"bootstrapSecret","username","password");
        String secret=string(request.get("bootstrapSecret")),username=name(request.get("username")),password=password(request.get("password"));
        if(c.state.mutation||c.state.selection||c.state.scoped)c.fail("VALIDATION_FAILED","Bootstrap must be the transaction's only action");c.state.mutation=true;
        c.server.guard(c.state,false);
        c.server.lockServer(c.state);
        if(c.server.revision(c.state)!=0||repo.bootstrapped()||!c.server.acceptsBootstrap(secret))c.fail("AUTH_FAILED","Bootstrap unavailable or secret invalid");
        Node user=repo.create("User",null,null,Json.map("username",username,"passwordHash",hashPassword(password),"enabled",true,"serverRole","administrator"));
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
        boolean valid=user!=null && Boolean.TRUE.equals(user.getProperty("enabled",false));
        if(password.getBytes(StandardCharsets.UTF_8).length>72)valid=false;
        if(valid) {char[] chars=password.toCharArray();try {valid=OpenBSDBCrypt.checkPassword((String)user.getProperty("passwordHash"),chars);}finally {Arrays.fill(chars,'\0');}}
        if(!valid) {c.server.loginFailed(username,origin);c.fail("AUTH_FAILED","Invalid credentials");}
        String token=ServerContext.randomToken();var session=new ServerContext.Session(CoreRepository.id(user),internal.securityContext().subject().executingUser(),c.server.sessionTime());
        c.state.afterCommit.add(()->c.server.sessions.put(Json.hash(token),session));c.revision=c.server.revision(c.state);
        session.baselines.put("server",c.revision);
        return new LoginRow(token,Json.map("user",repo.data(user),"expiresAt",Instant.ofEpochMilli(session.created+1_800_000).toString()),c.meta());
    }
    /** Browser credentials share the same hash and throttle policy but create a transport-bound session. */
    public String loginBrowser(String username,String password,String origin) {
        c.server.guard(c.state,false);
        username=name(username);password=string(password);
        c.server.checkLoginDelay(username,origin,c.state.kernel);
        Node user=repo.find("User","username",username);
        boolean valid=user!=null&&Boolean.TRUE.equals(user.getProperty("enabled",false))&&matchesPassword(user,password);
        if(!valid){c.server.loginFailed(username,origin);c.fail("AUTH_FAILED","Invalid credentials");}
        String token=ServerContext.randomToken();var session=new ServerContext.Session(CoreRepository.id(user),null,c.server.sessionTime(),ServerContext.Transport.BROWSER);
        session.csrfToken=ServerContext.randomToken();c.state.afterCommit.add(()->c.server.sessions.put(Json.hash(token),session));
        c.revision=c.server.revision(c.state);return token;
    }
    public ValueRow logout(Map<String,Object> request) {
        fields(request);readContext();authenticate();
        if(c.state.scoped||c.state.mutation)c.fail("VALIDATION_FAILED","Logout requires separate transaction");
        c.state.selection=true;c.state.afterCommit.add(()->c.server.sessions.remove(Json.hash(c.token)));c.revision=c.server.revision(c.state);return new ValueRow(true,c.meta());
    }
    public Outcome createUser(Map<String,Object> request) {
        fields(request,"username","password","serverRole");admin(authenticate());
        return action("deposit.create.user",request,true,null,()->{
            String username=name(request.get("username")),role=string(request.getOrDefault("serverRole","user"));
            if(!Set.of("user","administrator").contains(role))c.fail("VALIDATION_FAILED","Invalid serverRole");
            if(repo.find("User","username",username)!=null)c.fail("DUPLICATE","Username exists");
            Node n=repo.create("User",null,c.actorId,Json.map("username",username,"passwordHash",hashPassword(password(request.get("password"))),"enabled",true,"serverRole",role));return new Change(outcome(n),true);
        });
    }
    public Outcome createNamespace(Map<String,Object> request) {
        fields(request,"name","description","historyMode");
        return action("deposit.create.namespace",request,true,null,()->{
            String name=name(request.get("name")),mode=string(request.getOrDefault("historyMode","persistent"));
            if(!Set.of("disabled","transient","persistent").contains(mode))c.fail("VALIDATION_FAILED","Invalid historyMode");
            if(repo.find("Namespace","name",name)!=null)c.fail("DUPLICATE","Namespace name exists");
            Node ns=repo.create("Namespace",null,c.actorId,Json.map("name",name,"description",string(request.getOrDefault("description","")),"historyMode",mode,"historyEpochId",UUID.randomUUID().toString(),"sequence",0L,"depositVersion",DepositInfo.version()));
            repo.setRole(c.actorId,CoreRepository.id(ns),"owner");new deposit.repository.HistoryRepository(c.tx,repo).baseline(CoreRepository.id(ns),c.actorId,0,c.server.runId,c.timestamp);var data=repo.data(ns);data.put("role","owner");return new Change(new Outcome(ns,null,data),true);
        });
    }
    private static boolean matchesPassword(Node user, String password) {
        if(password.getBytes(StandardCharsets.UTF_8).length>72)return false;
        char[] chars=password.toCharArray();
        try {return OpenBSDBCrypt.checkPassword((String)user.getProperty("passwordHash"),chars);}
        finally {Arrays.fill(chars,'\0');}
    }
    public Outcome setUser(Map<String,Object> request) {
        fields(request,"userId","enabled","serverRole","newPassword");
        authorizationChange();admin(authenticate());
        return action("deposit.set.user",request,true,null,()->{
            Node target=repo.find("User","id",RequestContext.uuid(request.get("userId")));
            if(target==null)c.fail("NOT_FOUND","User not found");
            var changes=new LinkedHashMap<String,Object>();
            if(request.containsKey("enabled")) {
                if(!(request.get("enabled") instanceof Boolean))c.fail("VALIDATION_FAILED","enabled must be Boolean");
                changes.put("enabled",request.get("enabled"));
            }
            if(request.containsKey("serverRole")) {
                String role=string(request.get("serverRole"));
                if(!Set.of("user","administrator").contains(role))c.fail("VALIDATION_FAILED","Invalid serverRole");
                changes.put("serverRole",role);
            }
            boolean enabled=(Boolean)changes.getOrDefault("enabled",target.getProperty("enabled"));
            String role=(String)changes.getOrDefault("serverRole",target.getProperty("serverRole"));
            if((!enabled||!role.equals("administrator")) && Boolean.TRUE.equals(target.getProperty("enabled")) && AuthorizationAdapter.administrator(target)) {
                boolean other=repo.nodes("User",Map.of("enabled",true,"serverRole","administrator")).stream().anyMatch(u->!u.equals(target));
                if(!other)c.fail("VALIDATION_FAILED","Server requires an enabled administrator");
            }
            if(!enabled)repo.validateOwnerDeactivation(CoreRepository.id(target));
            boolean passwordChanged=false;
            if(request.containsKey("newPassword")) {
                String value=password(request.get("newPassword"));
                passwordChanged=!matchesPassword(target,value);
                if(passwordChanged)changes.put("passwordHash",hashPassword(value));
            }
            boolean disabled=Boolean.TRUE.equals(target.getProperty("enabled"))&&!enabled;
            boolean demoted=AuthorizationAdapter.administrator(target)&&!role.equals("administrator");
            boolean changed=repo.update(target,changes,c.actorId);
            String targetId=CoreRepository.id(target);
            if(changed&&targetId.equals(c.actorId))c.state.selfAccountChange=true;
            if(passwordChanged||disabled)c.state.afterCommit.add(()->c.server.revokeUser(targetId));
            if(demoted)c.state.afterCommit.add(()->c.server.demoteUser(targetId));
            return new Change(outcome(target),changed);
        });
    }
    public ValueRow setPassword(Map<String,Object> request) {
        fields(request,"oldPassword","newPassword");authorizationChange();
        Outcome result=action("deposit.set.password",request,true,null,()->{
            Node user=repo.find("User","id",c.actorId);
            if(!matchesPassword(user,string(request.get("oldPassword"))))c.fail("AUTH_FAILED","Invalid credentials");
            String value=password(request.get("newPassword"));boolean changed=!matchesPassword(user,value);
            if(changed) {
                repo.update(user,Map.of("passwordHash",hashPassword(value)),c.actorId);
                c.state.afterCommit.add(()->c.server.revokeUser(c.actorId));
            }
            return new Change(new Outcome(null,null,Json.map("changed",changed)),changed);
        });
        return new ValueRow(result.data().get("changed"),c.meta());
    }
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
    public Outcome setNamespace(Map<String,Object> request) {
        return new DomainService(c).execute("deposit.set.namespace",request);
    }
    public Outcome deleteNamespace(Map<String,Object> request) {
        fields(request,"confirmNamespaceName","expectedNamespaceRevision");
        authorizationChange();Node user=authenticate();mutation();
        repo.lockActor(c.actorId);c.server.lockServer(c.state);
        // Completed deletion recovery is server-scoped, independent of selection and live target.
        Node receipt=repo.receipt("server",c.operationId);
        if(receipt!=null&&receipt.hasProperty("deletedNamespaceId")) {
            c.replayed=true;
            if(!c.actorId.equals(receipt.getProperty("actorId")))admin(user);
            String target=(String)receipt.getProperty("deletedNamespaceId");
            if(!deletionHash(request,target).equals(receipt.getProperty("requestHash")))c.fail("OPERATION_MISMATCH","Operation input changed");
            c.revision=c.server.revision(c.state);return repo.replay(receipt,"server");
        }
        Node binding=repo.binding(c.actorId,c.operationId);
        if(binding!=null&&!"server".equals(binding.getProperty("scope")))c.fail("SCOPE_MISMATCH","Operation belongs to another scope");
        if(receipt!=null)c.fail("OPERATION_MISMATCH","Operation belongs to another request");
        scoped("owner");String target=c.namespaceId;repo.lockNamespace(target);requireRole(target,"owner");
        long sequence=repo.sequence(target);c.revision=c.server.revision(c.state);checkRevision();
        if(integer(request.get("expectedNamespaceRevision"))!=sequence)c.fail("REVISION_CONFLICT","Namespace revision changed");
        if(!string(request.get("confirmNamespaceName")).equals(repo.namespace(target).getProperty("name")))c.fail("VALIDATION_FAILED","Namespace name confirmation changed");
        String hash=deletionHash(request,target);List<String> deleted=repo.deleteNamespace(target);
        c.namespaceId=null;c.state.serverChanged=true;c.revision=c.server.revision(c.state);
        Outcome result=new Outcome(null,null,Json.map("deletedIds",deleted));repo.saveReceipt(c,"server",hash,result);
        repo.receipt("server",c.operationId).setProperty("deletedNamespaceId",target);
        c.state.afterCommit.add(()->c.server.clearNamespace(target));return result;
    }
    private String deletionHash(Map<String,Object> request,String target) {
        return CursorCodec.requestHash(c,Json.write(Json.map("procedure","deposit.delete.namespace","request",request,"targetNamespaceId",target,"expectedRevision",c.expectedRevision)));
    }
    public EntityRow active(Map<String,Object> request,boolean set) {
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
    public Outcome setRole(Map<String,Object> request) {
        fields(request,"userId","role");
        authorizationChange();
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
