package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.AuthenticationNamespaceLifecycleIT.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;

/** Independent Bolt/graph assertions; never calls Deposit implementation helpers. */
@Timeout(60)
class AccountAuthorizationIT {
    static String admin;
    static final String PASS="authorization-user-password", NEW="authorization-changed-password";
    @BeforeAll static void setup(){
        connect();ensureAccount();admin=login(ACCOUNT,PASSWORD);
        String accountId=query("MATCH (u:User {username:$name}) RETURN u.id AS id",m("name",ACCOUNT)).getFirst().get("id").asString();
        if(!call("deposit.get.user",m("userId",accountId),ctx(admin)).get("data").get("serverRole").asString().equals("administrator")){
            boolean restored=false;
            for(var row:query("MATCH (u:User {enabled:true,serverRole:'administrator'}) WHERE u.username STARTS WITH 'authorization-user-' RETURN u.username AS name",Map.of())){
                for(String password:List.of(PASS,NEW))try{
                    String recoveryAdmin=login(row.get("name").asString(),password);
                    call("deposit.set.user",m("userId",accountId,"serverRole","administrator"),write(recoveryAdmin,sr(recoveryAdmin)));
                    admin=login(ACCOUNT,PASSWORD);restored=true;break;
                }catch(org.neo4j.driver.exceptions.ClientException ignored){}
                if(restored)break;
            }
            if(!restored)throw new IllegalStateException("The authorization suite requires its bootstrap account to be an administrator; restore that account before running it.");
        }
        for(var row:query("MATCH (u:User) WHERE u.username STARTS WITH 'authorization-user-' AND u.serverRole='administrator' RETURN u.id AS id",Map.of()))update(row.get("id").asString(),m("serverRole","user"));
    }
    @AfterAll static void finish() { close(); }
    static String login(String username,String password){return special("deposit.utils.login",m("username",username,"password",password)).get("value").asString();}
    static long sr(String token){return revision(call("deposit.get.user",Map.of(),ctx(token)));}
    static long observedServerRevision(String token){
        Object observed=query("CALL deposit.get.session($token) YIELD data RETURN data",m("token",token)).getFirst().get("data").get("observed").asMap().get("server");
        return ((Number)observed).longValue();
    }
    static void releaseAndRefreshServer(String token){
        query("CALL deposit.action.release($token)",m("token",token));
        query("CALL deposit.utils.refresh('server',$token)",m("token",token));
    }
    static org.neo4j.driver.Record positionalUserPatch(String token,String id){
        return query("CALL deposit.set.user({id:$id},{serverRole:'user'},$token) YIELD data RETURN data",m("id",id,"token",token)).getFirst();
    }
    static long nr(String token){return revision(call("deposit.get.activeNamespace",Map.of(),ctx(token)));}
    static String user(String role){String name="authorization-user-"+UUID.randomUUID();return id(call("deposit.create.user",m("username",name,"password",PASS,"serverRole",role),write(admin,sr(admin))));}
    static String username(String id){return call("deposit.get.user",m("userId",id),ctx(admin)).get("data").get("username").asString();}
    static String uniqueName(String prefix){return prefix+"-"+UUID.randomUUID();}
    static String ns(String token,String name){String id=id(call("deposit.create.namespace",m("name",name+"-"+UUID.randomUUID(),"historyMode","disabled"),write(token,sr(token))));select(token,id);return id;}
    static void select(String token,String id){call("deposit.set.activeNamespace",m("id",id),ctx(token));}
    static void role(String token,String user,String role){call("deposit.set.namespaceRole",m("userId",user,"role",role),write(token,nr(token)));}
    static void mode(String token,boolean flag){assertEquals(flag,call("deposit.set.administrativeMode",m("enabled",flag),ctx(token)).get("value").asBoolean());}
    static org.neo4j.driver.Record update(String id,Map<String,Object> fields){var request=new LinkedHashMap<String,Object>(fields);request.put("userId",id);return call("deposit.set.user",request,write(admin,sr(admin)));}
    static Map<String,Object> deletion(String token){var row=call("deposit.get.activeNamespace",Map.of(),ctx(token));return m("confirmNamespaceName",row.get("data").get("name").asString(),"expectedNamespaceRevision",revision(row));}
    static void authFails(String token){error("AUTH_FAILED",()->call("deposit.get.user",Map.of(),ctx(token)));}
    static String cursor(String token){String value=call("deposit.get.namespacePage",m("pageSize",1L),ctx(token)).get("meta").get("nextCursor").asString(null);assertNotNull(value);return value;}
    static void stale(String token,String cursor){error("REVISION_CONFLICT",()->call("deposit.get.namespacePage",m("pageSize",1L,"cursor",cursor),ctx(token)));}

    @Test void lastAdministratorDisableAndDemotionAreAtomic() throws Exception {
        String own=id(call("deposit.get.user",m("userId",query("MATCH (u:User {username:$name}) RETURN u.id AS id",m("name",ACCOUNT)).getFirst().get("id").asString()),ctx(admin)));
        long before=sr(admin);
        error("VALIDATION_FAILED",()->update(own,m("enabled",false)));
        error("VALIDATION_FAILED",()->update(own,m("serverRole","user")));
        assertEquals(before,sr(admin));
        String other=user("administrator");String token=login(username(other),PASS);long expected=sr(admin);
        releaseAndRefreshServer(admin);releaseAndRefreshServer(token);
        assertEquals(expected,observedServerRevision(admin),"Primary admin did not observe the race revision");
        assertEquals(expected,observedServerRevision(token),"Second admin did not observe the race revision");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return positionalUserPatch(admin,own);});
            var b=pool.submit(()->{start.await();return positionalUserPatch(token,other);});start.countDown();
            int successes=0;for(var task:List.of(a,b))try {task.get(15,TimeUnit.SECONDS);successes++;}catch(ExecutionException e){Throwable cause=e.getCause();assertTrue(cause.getMessage().contains("\"code\":\"REVISION_CONFLICT\""),"Unexpected concurrent demotion failure: "+cause.getClass().getName()+": "+cause.getMessage());}assertEquals(1,successes,"Exactly one administrator demotion must commit");
        }
        assertEquals(1,query("MATCH (u:User {enabled:true,serverRole:'administrator'}) RETURN count(u) AS n",Map.of()).getFirst().get("n").asInt());
        assertEquals(expected+1,sr(token),"The concurrent server actions must advance the revision once");
        boolean selfDemoted=call("deposit.get.user",m("userId",own),ctx(admin)).get("data").get("serverRole").asString().equals("user");
        if(selfDemoted){call("deposit.set.user",m("userId",own,"serverRole","administrator"),write(token,sr(token)));update(other,m("serverRole","user"));}
    }
    @Test void passwordNoOpChangeRollbackAndReplay() {
        String id=user("user"),name=username(id),a=login(name,PASS),b=login(name,PASS);
        Map<String,Object> original=query("MATCH (u:User {id:$id}) RETURN properties(u) AS p",m("id",id)).getFirst().get("p").asMap();
        long revision=sr(admin);var noOp=write(a,revision);
        assertFalse(call("deposit.set.password",m("oldPassword",PASS,"newPassword",PASS),noOp).get("value").asBoolean());
        assertEquals(revision,sr(b));assertTrue(original.equals(query("MATCH (u:User {id:$id}) RETURN properties(u) AS p",m("id",id)).getFirst().get("p").asMap()),"No-op changed private account state");
        var unchanged=update(id,m("newPassword",PASS));assertFalse(unchanged.get("data").get("changed").asBoolean());assertEquals(revision,sr(a));
        error("AUTH_FAILED",()->call("deposit.set.password",m("oldPassword","incorrect","newPassword",NEW),write(a,revision)));
        error("VALIDATION_FAILED",()->call("deposit.set.password",m("oldPassword",PASS,"newPassword","short"),write(a,revision)));
        error("VALIDATION_FAILED",()->call("deposit.set.password",m("oldPassword",PASS,"newPassword","é".repeat(37)),write(a,revision)));
        var request=m("oldPassword",PASS,"newPassword",NEW);var context=write(a,revision);
        try(var s=driver.session();var tx=s.beginTransaction()){tx.run("CALL deposit.set.password($r,$c)",m("r",request,"c",context)).consume();tx.rollback();}
        assertEquals(revision,sr(b));
        assertTrue(call("deposit.set.password",request,context).get("value").asBoolean());authFails(a);authFails(b);
        String fresh=login(name,NEW);var retry=new LinkedHashMap<String,Object>(context);retry.put("token",fresh);
        assertTrue(call("deposit.set.password",request,retry).get("value").asBoolean());assertEquals(revision+1,sr(fresh));
        error("OPERATION_MISMATCH",()->call("deposit.set.password",m("oldPassword",PASS,"newPassword",PASS),retry));
        assertFalse(call("deposit.get.operation",m("operationId",context.get("operationId"),"scope","server"),ctx(fresh)).get("data").asMap().toString().contains(PASS));
    }
    @Test void accountResetDisableReenableAndReceiptPermissions() {
        String id=user("user"),name=username(id),a=login(name,PASS),b=login(name,PASS);
        long before=sr(admin);var context=write(admin,before);var request=m("userId",id,"newPassword",NEW);
        var result=call("deposit.set.user",request,context);assertEquals(before+1,revision(result));assertFalse(result.get("data").asMap().containsKey("passwordHash"));authFails(a);authFails(b);
        String fresh=login(name,NEW);call("deposit.set.user",request,context);sr(fresh);
        update(id,m("enabled",false));authFails(fresh);error("AUTH_FAILED",()->login(name,NEW));
        update(id,m("enabled",true));fresh=login(name,NEW);sr(fresh);
        final String token=fresh;error("FORBIDDEN",()->call("deposit.set.user",m("userId",id,"serverRole","administrator"),write(token,sr(token))));
        update(id,m("serverRole","administrator"));String promoted=login(name,NEW);assertEquals("administrator",call("deposit.get.user",m("userId",id),ctx(promoted)).get("data").get("serverRole").asString());update(id,m("serverRole","user"));
    }
    @Test void ownerDeactivationAndRoleRaceRetainEnabledOwner() throws Exception {
        String one=user("user"),two=user("user"),token=login(username(one),PASS),other=login(username(two),PASS);
        String namespace=ns(token,"authorization-owner-race");
        error("VALIDATION_FAILED",()->update(one,m("enabled",false)));role(token,two,"owner");select(other,namespace);
        long expectedServer=sr(admin),expectedNs=nr(token);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return call("deposit.set.user",m("userId",one,"enabled",false),write(admin,expectedServer));});
            var b=pool.submit(()->{start.await();return call("deposit.set.namespaceRole",m("userId",two,"role","viewer"),write(other,expectedNs));});start.countDown();
            int successes=0;for(var task:List.of(a,b))try{task.get(15,TimeUnit.SECONDS);successes++;}catch(ExecutionException e){assertTrue(e.getCause().getMessage().contains("VALIDATION_FAILED"));}assertEquals(1,successes);
        }
        assertEquals(1,query("MATCH (u:User {enabled:true})-[r:HAS_NAMESPACE_ROLE {role:'owner'}]->(n:Namespace {id:$id}) RETURN count(u) AS n",m("id",namespace)).getFirst().get("n").asInt());
    }
    @Test void administrativeModeIsolationRollbackCaptureAndDemotion() throws Exception {
        String privileged=user("administrator"),name=username(privileged),a=login(name,PASS),b=login(name,PASS);
        String owner=user("user"),ownerToken=login(username(owner),PASS),namespace=ns(ownerToken,"authorization-mode-private");
        assertFalse(call("deposit.get.administrativeMode",Map.of(),ctx(a)).get("value").asBoolean());
        error("NOT_FOUND",()->select(a,namespace));long before=sr(a);mode(a,true);assertEquals(before,sr(a));
        assertFalse(call("deposit.get.administrativeMode",Map.of(),ctx(b)).get("value").asBoolean());
        error("NO_ACTIVE_NAMESPACE",()->call("deposit.get.class",Map.of(),ctx(a)));select(a,namespace);assertEquals("owner",call("deposit.get.activeNamespace",Map.of(),ctx(a)).get("data").get("role").asString());
        try(var s=driver.session();var tx=s.beginTransaction()){tx.run("CALL deposit.set.administrativeMode({enabled:false},$c)",m("c",ctx(a))).consume();tx.rollback();}assertTrue(call("deposit.get.administrativeMode",Map.of(),ctx(a)).get("value").asBoolean());
        try(var s=driver.session();var tx=s.beginTransaction()) {
            tx.run("CALL deposit.get.class({},$c)",m("c",ctx(a))).consume();mode(a,false);
            tx.run("CALL deposit.get.class({},$c)",m("c",ctx(a))).consume();tx.commit();
        }
        error("NOT_FOUND",()->call("deposit.get.class",Map.of(),ctx(a)));mode(a,true);mode(b,true);
        try(var s=driver.session();var tx=s.beginTransaction()){tx.run("CALL deposit.get.class({},$c)",m("c",ctx(a))).consume();error("VALIDATION_FAILED",()->tx.run("CALL deposit.set.administrativeMode({enabled:false},$c)",m("c",ctx(a))).consume());}
        try(var s=driver.session();var tx=s.beginTransaction()){tx.run("CALL deposit.set.administrativeMode({enabled:true},$c)",m("c",ctx(a))).consume();error("VALIDATION_FAILED",()->tx.run("CALL deposit.get.class({},$c)",m("c",ctx(a))).consume());}
        update(privileged,m("serverRole","user"));assertFalse(call("deposit.get.administrativeMode",Map.of(),ctx(a)).get("value").asBoolean());assertFalse(call("deposit.get.administrativeMode",Map.of(),ctx(b)).get("value").asBoolean());error("FORBIDDEN",()->mode(a,true));error("NOT_FOUND",()->select(a,namespace));
    }
    @Test void authorityChangesWaitForProtectedCommit() throws Exception {
        String elevated=user("administrator"),a=login(username(elevated),PASS);String owner=user("user"),o=login(username(owner),PASS);String namespace=ns(o,"authorization-commit-authority");mode(a,true);select(a,namespace);
        var writeContext=write(a,0);
        try(var s=driver.session();var tx=s.beginTransaction();var pool=Executors.newSingleThreadExecutor()) {
            tx.run("CALL deposit.create.class({name:'Committed'},$c)",m("c",writeContext)).consume();
            long expected=sr(admin);var entered=new CountDownLatch(1);
            var change=pool.submit(()->{entered.countDown();return call("deposit.set.user",m("userId",elevated,"serverRole","user"),write(admin,expected));});
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->change.get(200,TimeUnit.MILLISECONDS));tx.commit();change.get(15,TimeUnit.SECONDS);
        }
        assertEquals(1,nr(o));error("NOT_FOUND",()->call("deposit.create.class",m("name","Denied"),write(a,1)));
        var audit=query("MATCH (r:DepositReceipt {operationId:$op}) RETURN r.actorId AS actor,r.administrativeMode AS administrative",m("op",writeContext.get("operationId"))).getFirst();assertEquals(elevated,audit.get("actor").asString());assertTrue(audit.get("administrative").asBoolean());
    }
    @Test void discoveryFingerprintCoversNamespaceEditsRolesAndMode() {
        String namespace=ns(admin,"authorization-discovery-a");ns(admin,"authorization-discovery-b");select(admin,namespace);
        String page=cursor(admin);long before=sr(admin);call("deposit.set.namespace",m("name",uniqueName("authorization-discovery-renamed")),write(admin,nr(admin)));assertEquals(before,sr(admin));stale(admin,page);assertEquals(namespace,id(call("deposit.get.activeNamespace",Map.of(),ctx(admin))));
        page=cursor(admin);call("deposit.create.class",m("name","Changes-sequence"),write(admin,nr(admin)));stale(admin,page);
        page=cursor(admin);mode(admin,true);stale(admin,page);mode(admin,false);
        String id=user("user"),token=login(username(id),PASS);role(admin,id,"viewer");String n2=ns(admin,"authorization-discovery-c");role(admin,id,"viewer");
        String membershipPage=cursor(token);role(admin,id,null);stale(token,membershipPage);role(admin,id,"viewer");
        String additionPage=cursor(token);String n3=ns(admin,"authorization-discovery-d");role(admin,id,"viewer");stale(token,additionPage);
        List<Object> nativeRows=query("CALL deposit.get.namespace({},$c) YIELD data RETURN data",m("c",ctx(token))).stream().map(r->(Object)r.get("data").asMap()).toList();
        List<Object> pages=new ArrayList<>();String next=null;do{var req=m("pageSize",1L);if(next!=null)req.put("cursor",next);var row=call("deposit.get.namespacePage",req,ctx(token));pages.addAll(row.get("data").get("items").asList());next=row.get("meta").get("nextCursor").asString(null);}while(next!=null);assertEquals(nativeRows,pages);
        String emptyId=user("user"),empty=login(username(emptyId),PASS);assertTrue(call("deposit.get.namespacePage",Map.of(),ctx(empty)).get("data").get("items").asList().isEmpty());error("NOT_FOUND",()->call("deposit.get.namespace",m("id",namespace),ctx(empty)));
    }
    @Test void namespaceDeletionPreconditionsRollbackAndReplay() throws Exception {
        String owner=user("user"),name=username(owner),a=login(name,PASS),b=login(name,PASS);String namespace=ns(a,"authorization-delete");select(b,namespace);
        call("deposit.create.class",m("name","Specimen"),write(a,0));call("deposit.create.object",m("className","Specimen","descriptors",m("Mass",m("type","Integer","value",10L))),write(a,1));
        var request=deletion(a);long server=sr(a);var context=write(a,server);
        error("VALIDATION_FAILED",()->call("deposit.delete.namespace",m("confirmNamespaceName","wrong","expectedNamespaceRevision",nr(a)),write(a,server)));
        error("REVISION_CONFLICT",()->call("deposit.delete.namespace",m("confirmNamespaceName",request.get("confirmNamespaceName"),"expectedNamespaceRevision",0L),write(b,server-1)));
        error("REVISION_CONFLICT",()->call("deposit.delete.namespace",request,write(b,server-1)));
        try(var s=driver.session();var tx=s.beginTransaction()){tx.run("CALL deposit.delete.namespace($r,$c)",m("r",request,"c",context)).consume();tx.rollback();}
        assertEquals(namespace,id(call("deposit.get.activeNamespace",Map.of(),ctx(b))));assertEquals(server,sr(a));error("NOT_FOUND",()->call("deposit.get.operation",m("operationId",context.get("operationId"),"scope","server"),ctx(a)));
        var result=call("deposit.delete.namespace",request,context);assertEquals(server+1,revision(result));assertTrue(result.get("meta").get("namespaceId").isNull());assertTrue(result.get("data").get("deletedIds").asList().contains(namespace));
        for(String token:List.of(a,b))assertTrue(call("deposit.get.activeNamespace",Map.of(),ctx(token)).get("data").isNull());
        assertEquals(0,query("MATCH (n) WHERE NOT n:DepositPreparedAction AND (n.namespaceId=$id OR (n:Namespace AND n.id=$id)) RETURN count(n) AS n",m("id",namespace)).getFirst().get("n").asInt());
        var replay=call("deposit.delete.namespace",request,context);assertEquals(result.get("data").asMap(),replay.get("data").asMap());String survivor=ns(a,"authorization-delete-survivor");call("deposit.delete.namespace",request,context);assertEquals(survivor,id(call("deposit.get.activeNamespace",Map.of(),ctx(a))));
        var adminRetry=new LinkedHashMap<String,Object>(context);adminRetry.put("token",admin);assertEquals(result.get("data").asMap(),call("deposit.get.operation",m("operationId",context.get("operationId"),"scope","server"),ctx(admin)).get("data").get("outcome").asMap());
        var mismatch=new LinkedHashMap<String,Object>(request);mismatch.put("confirmNamespaceName","changed");error("OPERATION_MISMATCH",()->call("deposit.delete.namespace",mismatch,context));
        Properties recovery=new Properties();recovery.setProperty("username",name);recovery.setProperty("actor",owner);recovery.setProperty("deleted",namespace);recovery.setProperty("survivor",survivor);recovery.setProperty("operation",(String)context.get("operationId"));recovery.setProperty("server",Long.toString(server));recovery.setProperty("namespaceRevision",request.get("expectedNamespaceRevision").toString());recovery.setProperty("name",request.get("confirmNamespaceName").toString());
        try(var out=Files.newOutputStream(ROOT.resolve("authorization-fixture.properties"))){recovery.store(out,"authorization recovery identities and deletion receipt");}
    }
    @Test void ordinaryPermissionsLogoutAndMixedSessions() {
        String owner=user("user"),viewer=user("user"),editor=user("user");String a=login(username(owner),PASS),v=login(username(viewer),PASS),e=login(username(editor),PASS),other=login(username(owner),PASS);String namespace=ns(a,"authorization-role-matrix");role(a,viewer,"viewer");role(a,editor,"editor");select(v,namespace);select(e,namespace);
        error("FORBIDDEN",()->call("deposit.create.class",m("name","Denied"),write(v,nr(v))));call("deposit.create.class",m("name","Allowed"),write(e,nr(e)));
        error("FORBIDDEN",()->call("deposit.set.namespace",m("description","denied"),write(e,nr(e))));error("FORBIDDEN",()->call("deposit.delete.namespace",deletion(v),write(v,sr(v))));
        error("FORBIDDEN",()->mode(e,true));error("AUTH_REQUIRED",()->call("deposit.get.user",Map.of(),ctx("")));error("AUTH_FAILED",()->call("deposit.get.user",Map.of(),ctx("invalid")));
        try(var s=driver.session();var tx=s.beginTransaction()){tx.run("CALL deposit.get.class({},$c)",m("c",ctx(a))).consume();error("VALIDATION_FAILED",()->tx.run("CALL deposit.get.class({},$c)",m("c",ctx(v))).consume());}
        call("deposit.utils.logout",Map.of(),ctx(a));authFails(a);sr(other);
        assertTrue(call("deposit.get.activeNamespace",Map.of(),ctx(other)).get("data").isNull());
        try(var s=driver.session();var tx=s.beginTransaction()){tx.run("CALL deposit.utils.logout({},$c)",m("c",ctx(other))).consume();tx.rollback();}sr(other);
    }
    @Test void discoveryStreamFailsAfterConcurrentNamespaceRename() {
        String namespace=ns(admin,"authorization-stream-before");String writer=login(ACCOUNT,PASSWORD);select(writer,namespace);
        try(var streaming=GraphDatabase.driver(System.getenv("DEPOSIT_TEST_URI"),AuthTokens.basic("neo4j",System.getenv("DEPOSIT_TEST_PASSWORD")),Config.builder().withFetchSize(1).build());var session=streaming.session();var tx=session.beginTransaction()) {
            var result=tx.run("CALL deposit.get.namespace(null,$token)",m("token",admin));assertTrue(result.hasNext());result.next();
            call("deposit.set.namespace",m("name",uniqueName("authorization-stream-after")),write(writer,nr(writer)));
            error("REVISION_CONFLICT",()->{while(result.hasNext())result.next();tx.commit();});
        }
    }
    @Test void administratorMutationReplayRequiresCurrentAuthority() {
        String administrator=user("administrator"),token=login(username(administrator),PASS),target=user("user");
        var request=m("userId",target,"serverRole","administrator");var context=write(token,sr(token));call("deposit.set.user",request,context);
        update(administrator,m("serverRole","user"));
        error("FORBIDDEN",()->call("deposit.set.user",request,context));
        // Receipt lookup remains available to the enabled originating actor even after demotion.
        assertEquals("committed",call("deposit.get.operation",m("operationId",context.get("operationId"),"scope","server"),ctx(token)).get("data").get("state").asString());
        String stranger=login(username(target),PASS);update(target,m("serverRole","user"));
        error("FORBIDDEN",()->call("deposit.get.operation",m("operationId",context.get("operationId"),"scope","server"),ctx(stranger)));
        update(administrator,m("enabled",false));authFails(token);
    }
    @Test void loginWaitsForDisableAndRollbackPreservesCredentials() throws Exception {
        String id=user("user"),name=username(id);long expected=sr(admin);
        try(var s=driver.session();var tx=s.beginTransaction();var pool=Executors.newSingleThreadExecutor()) {
            tx.run("CALL deposit.set.user($r,$c)",m("r",m("userId",id,"enabled",false),"c",write(admin,expected))).consume();var entered=new CountDownLatch(1);
            var login=pool.submit(()->{entered.countDown();return login(name,PASS);});assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->login.get(200,TimeUnit.MILLISECONDS));tx.rollback();sr(login.get(15,TimeUnit.SECONDS));
        }
        try(var s=driver.session();var tx=s.beginTransaction();var pool=Executors.newSingleThreadExecutor()) {
            tx.run("CALL deposit.set.user($r,$c)",m("r",m("userId",id,"enabled",false),"c",write(admin,expected))).consume();var entered=new CountDownLatch(1);
            var login=pool.submit(()->{entered.countDown();return login(name,PASS);});assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->login.get(200,TimeUnit.MILLISECONDS));tx.commit();var failure=assertThrows(ExecutionException.class,()->login.get(15,TimeUnit.SECONDS));assertTrue(failure.getCause().getMessage().contains("AUTH_FAILED"));
        }
    }
    @Test void deletionRechecksNamespaceAfterInterveningWriteAndRename() throws Exception {
        String namespace=ns(admin,"authorization-delete-race");String other=login(ACCOUNT,PASSWORD);select(other,namespace);var request=deletion(admin);var context=write(other,sr(admin));
        try(var s=driver.session();var tx=s.beginTransaction();var pool=Executors.newSingleThreadExecutor()) {
            tx.run("CALL deposit.create.class({name:'Kept'},$c)",m("c",write(admin,0))).consume();var entered=new CountDownLatch(1);
            var deletion=pool.submit(()->{entered.countDown();return call("deposit.delete.namespace",request,context);});assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->deletion.get(200,TimeUnit.MILLISECONDS));tx.commit();var failure=assertThrows(ExecutionException.class,()->deletion.get(15,TimeUnit.SECONDS));assertTrue(failure.getCause().getMessage().contains("REVISION_CONFLICT"));
        }
        assertEquals(namespace,id(call("deposit.get.activeNamespace",Map.of(),ctx(other))));var old=deletion(admin);call("deposit.set.namespace",m("name",uniqueName("authorization-delete-race-renamed")),write(admin,nr(admin)));old.put("expectedNamespaceRevision",nr(admin));error("VALIDATION_FAILED",()->call("deposit.delete.namespace",old,write(admin,sr(admin))));
    }
    @Test void accountLogicalNoOpsAndSelfDisableRetainAuditAndCounters() {
        String id=user("administrator"),token=login(username(id),PASS);var original=call("deposit.get.user",m("userId",id),ctx(admin)).get("data").asMap();long before=sr(admin);
        var unchanged=update(id,m("enabled",true,"serverRole","administrator"));assertFalse(unchanged.get("data").get("changed").asBoolean());assertEquals(before,sr(admin));assertEquals(original,call("deposit.get.user",m("userId",id),ctx(admin)).get("data").asMap());
        call("deposit.set.user",m("userId",id,"enabled",false),write(token,before));authFails(token);assertEquals(before+1,sr(admin));
    }
    @Test void authenticatedReadCanPrecedeAuthorizationMutation() {
        String id=user("user");long before=sr(admin);
        try(var s=driver.session();var tx=s.beginTransaction()) {
            tx.run("CALL deposit.get.user({},$c)",m("c",ctx(admin))).consume();
            tx.run("CALL deposit.set.user($r,$c)",m("r",m("userId",id,"serverRole","administrator"),"c",write(admin,before))).consume();
            tx.run("CALL deposit.get.user($r,$c)",m("r",m("userId",id),"c",ctx(admin))).consume();tx.commit();
        }
        assertEquals(before+1,sr(admin));update(id,m("serverRole","user"));
    }
}
