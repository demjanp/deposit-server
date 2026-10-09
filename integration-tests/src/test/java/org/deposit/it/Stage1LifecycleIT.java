package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;

/** Independent installed-runtime checks for the commit boundary; no implementation dependencies. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(45)
class Stage1LifecycleIT {
    static Driver driver;
    static String token,otherToken,userId,ns,otherNs;
    static long serverRevision;
    static final String ACCOUNT="stage1-admin", PASSWORD="stage1-account-password";
    static final Path ROOT=Path.of("C:/data_processed/Deposit_v2/stage0/runs/stage0-host");
    @BeforeAll static void connect() {driver=GraphDatabase.driver(System.getenv("STAGE0_URI"),AuthTokens.basic("neo4j",System.getenv("STAGE0_PASSWORD")),Config.builder().withFetchSize(17).build());driver.verifyConnectivity();}
    @AfterAll static void close() {if(driver!=null)driver.close();}
    static Map<String,Object> m(Object... kv) {var m=new LinkedHashMap<String,Object>();for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]);return m;}
    static Map<String,Object> ctx(String token) {return m("token",token,"requestId",UUID.randomUUID().toString());}
    static Map<String,Object> write(String token,long revision) {var c=ctx(token);c.put("operationId",UUID.randomUUID().toString());c.put("expectedRevision",revision);return c;}
    static List<org.neo4j.driver.Record> query(String query,Map<String,Object> params) {try(var s=driver.session()) {return s.run("CYPHER 25 "+query,params,TransactionConfig.builder().withTimeout(Duration.ofSeconds(30)).build()).list();}}
    static org.neo4j.driver.Record call(String procedure,Map<String,Object> request,Map<String,Object> context) {return query("CALL "+procedure+"($r,$c)",m("r",request,"c",context)).getFirst();}
    static org.neo4j.driver.Record special(String procedure,Map<String,Object> request) {return query("CALL "+procedure+"($r)",m("r",request)).getFirst();}
    static long revision(org.neo4j.driver.Record row) {return row.get("meta").get("revision").asLong();}
    static String id(org.neo4j.driver.Record row) {return row.get("data").get("id").asString();}
    static void error(String code,Runnable action) {var e=assertThrows(org.neo4j.driver.exceptions.ClientException.class,action::run);assertTrue(e.getMessage().contains("\"code\":\""+code+"\""),e.getMessage());}

    @Test @Order(1) void bootstrapRollbackThenCommitAndLoginReadOnlyCompletion() throws Exception {
        assertEquals("0.0.0-stage4",query("CALL deposit.get.info() YIELD data RETURN data",Map.of()).getFirst().get("data").get("version").asString());
        String secret=Files.readString(ROOT.resolve("neo4j/conf/deposit-bootstrap.secret")).trim();
        var request=m("bootstrapSecret",secret,"username",ACCOUNT,"password",PASSWORD);
        try(var s=driver.session();var tx=s.beginTransaction()) {assertEquals(1,tx.run("CALL deposit.utils.bootstrap($r)",m("r",request)).single().get("meta").get("revision").asLong());tx.rollback();}
        assertTrue(Files.exists(ROOT.resolve("neo4j/conf/deposit-bootstrap.secret")));
        assertEquals(0,query("MATCH (u:User) RETURN count(u) AS n",Map.of()).getFirst().get("n").asInt());
        var result=special("deposit.utils.bootstrap",request);userId=id(result);assertEquals(1,revision(result));
        assertFalse(Files.exists(ROOT.resolve("neo4j/conf/deposit-bootstrap.secret")));
        error("AUTH_FAILED",()->special("deposit.utils.bootstrap",request));
        var login=special("deposit.utils.login",m("username",ACCOUNT,"password",PASSWORD));token=login.get("value").asString();serverRevision=revision(login);
        otherToken=special("deposit.utils.login",m("username",ACCOUNT,"password",PASSWORD)).get("value").asString();assertNotEquals(token,otherToken);
        assertTrue(call("deposit.get.activeNamespace",Map.of(),ctx(token)).get("data").isNull());
        String rolled;
        try(var s=driver.session();var tx=s.beginTransaction()) {rolled=tx.run("CALL deposit.utils.login($r)",m("r",m("username",ACCOUNT,"password",PASSWORD))).single().get("value").asString();tx.rollback();}
        error("AUTH_FAILED",()->call("deposit.get.activeNamespace",Map.of(),ctx(rolled)));
    }
    @Test @Order(2) void serverLockSurvivesProcedureReturnAndRollbackReleasesIt() throws Exception {
        var request=m("name","N1","historyMode","disabled");var context=write(token,serverRevision);
        try(var first=driver.session();var transaction=first.beginTransaction();var executor=Executors.newSingleThreadExecutor()) {
            var pending=transaction.run("CALL deposit.create.namespace($r,$c)",m("r",request,"c",context)).single();ns=id(pending);
            var entered=new CountDownLatch(1);
            Future<org.neo4j.driver.Record> competing=executor.submit(()->{entered.countDown();return call("deposit.create.namespace",m("name","competing","historyMode","disabled"),write(otherToken,serverRevision));});
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->competing.get(250,TimeUnit.MILLISECONDS));
            transaction.commit();var failure=assertThrows(ExecutionException.class,()->competing.get(10,TimeUnit.SECONDS));assertTrue(failure.getCause().getMessage().contains("REVISION_CONFLICT"));serverRevision=revision(pending);
        }
        try(var s=driver.session();var tx=s.beginTransaction()) {tx.run("CALL deposit.create.namespace($r,$c)",m("r",m("name","rolled-back","historyMode","disabled"),"c",write(token,serverRevision))).consume();tx.rollback();}
        var result=call("deposit.create.namespace",m("name","N2","historyMode","disabled"),write(token,serverRevision));otherNs=id(result);serverRevision=revision(result);
        var replay=call("deposit.create.namespace",request,context);assertEquals(ns,id(replay));assertEquals(serverRevision,revision(replay));
        error("HISTORY_UNAVAILABLE",()->call("deposit.create.namespace",m("name","default-history"),write(token,serverRevision)));
    }
    @Test @Order(3) void selectionCommitRollbackAndIndependentTokens() {
        call("deposit.set.activeNamespace",m("id",ns),ctx(token));call("deposit.set.activeNamespace",m("name","N2"),ctx(otherToken));
        assertEquals(ns,id(call("deposit.get.activeNamespace",Map.of(),ctx(token))));assertEquals(otherNs,id(call("deposit.get.activeNamespace",Map.of(),ctx(otherToken))));
        try(var s=driver.session();var tx=s.beginTransaction()) {tx.run("CALL deposit.set.activeNamespace($r,$c)",m("r",m("id",otherNs),"c",ctx(token))).consume();tx.rollback();}
        assertEquals(ns,id(call("deposit.get.activeNamespace",Map.of(),ctx(token))));
        error("NOT_FOUND",()->call("deposit.set.activeNamespace",m("name","absent"),ctx(token)));
        try(var s=driver.session();var tx=s.beginTransaction()) {
            tx.run("CALL deposit.get.class({},$c)",m("c",ctx(token))).consume();
            error("VALIDATION_FAILED",()->tx.run("CALL deposit.set.activeNamespace($r,$c)",m("r",m("id",otherNs),"c",ctx(token))).consume());
        }
        try(var s=driver.session();var tx=s.beginTransaction()) {
            tx.run("CALL deposit.set.activeNamespace($r,$c)",m("r",m("id",otherNs),"c",ctx(token))).consume();
            error("VALIDATION_FAILED",()->tx.run("CALL deposit.get.class({},$c)",m("c",ctx(token))).consume());
        }
        assertEquals(ns,id(call("deposit.get.activeNamespace",Map.of(),ctx(token))));
    }
    @Test @Order(4) void namespaceRollbackReceiptAndNoOp() {
        var context=write(token,0);var request=m("name","Specimen","descriptors",List.of(m("name","Mass","type","Integer")));
        try(var s=driver.session();var tx=s.beginTransaction()) {tx.run("CALL deposit.create.class($r,$c)",m("r",request,"c",context)).consume();tx.rollback();}
        assertEquals(0,revision(call("deposit.get.activeNamespace",Map.of(),ctx(token))));
        error("NOT_FOUND",()->call("deposit.get.operation",m("operationId",context.get("operationId")),ctx(token)));
        var created=call("deposit.create.class",request,context);assertEquals(1,revision(created));assertEquals(id(created),id(call("deposit.create.class",request,context)));
        var unchanged=call("deposit.set.class",m("id",id(created),"name","Specimen"),write(token,1));assertFalse(unchanged.get("data").get("changed").asBoolean());assertEquals(1,revision(unchanged));
        error("OPERATION_MISMATCH",()->call("deposit.create.class",m("name","Different"),context));
        call("deposit.set.activeNamespace",m("id",otherNs),ctx(token));error("SCOPE_MISMATCH",()->call("deposit.create.class",request,context));call("deposit.set.activeNamespace",m("id",ns),ctx(token));
    }
    @Test @Order(5) void persistPublicFixtureIdentity() throws Exception {
        Files.writeString(Path.of(System.getenv("STAGE0_EVIDENCE")).resolve("stage1-lifecycle.tsv"),"N1\t"+ns+"\nN2\t"+otherNs+"\nadmin\t"+userId+"\n");
    }
}
