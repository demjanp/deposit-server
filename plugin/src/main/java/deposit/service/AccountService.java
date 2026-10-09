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
        c.server.lockServer(c.state);
        if(c.server.revision(c.state)!=0||repo.bootstrapped()||!c.server.acceptsBootstrap(secret))c.fail("AUTH_FAILED","Bootstrap unavailable or secret invalid");
        Node user=repo.create("User",null,null,Json.map("username",username,"passwordHash",hashPassword(password),"enabled",true,"serverRole","administrator"));
        repo.markBootstrap();c.state.serverChanged=true;c.state.afterCommit.add(c.server::consumeBootstrap);c.revision=c.server.revision(c.state);
        return new DataRow(repo.data(user),c.meta());
    }
    public LoginRow login(Map<String,Object> request) {
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
        String token=ServerContext.randomToken();var session=new ServerContext.Session(CoreRepository.id(user),internal.securityContext().subject().executingUser());
        c.state.afterCommit.add(()->c.server.sessions.put(Json.hash(token),session));c.revision=c.server.revision(c.state);
        return new LoginRow(token,Json.map("user",repo.data(user),"expiresAt",Instant.ofEpochMilli(session.created+1_800_000).toString()),c.meta());
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
            if(!mode.equals("disabled"))c.fail("HISTORY_UNAVAILABLE","Stage 1 supports explicitly disabled history only");
            if(repo.find("Namespace","name",name)!=null)c.fail("DUPLICATE","Namespace name exists");
            Node ns=repo.create("Namespace",null,c.actorId,Json.map("name",name,"description",string(request.getOrDefault("description","")),"historyMode",mode,"historyEpochId",UUID.randomUUID().toString(),"sequence",0L,"depositVersion",DepositInfo.version()));
            repo.setRole(c.actorId,CoreRepository.id(ns),"owner");var data=repo.data(ns);data.put("role","owner");return new Change(new Outcome(ns,null,data),true);
        });
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
        requireRole(namespace,"viewer");c.namespaceId=namespace;c.revision=repo.sequence(namespace);Node ns=repo.namespace(namespace);var data=repo.data(ns);data.put("role",repo.role(c.actorId,namespace));return new EntityRow(ns,data,c.meta());
    }
    public Outcome setRole(Map<String,Object> request) {
        fields(request,"userId","role");
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
