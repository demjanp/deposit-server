package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.AuthenticationNamespaceLifecycleIT.*;
import static org.deposit.it.AccountAuthorizationIT.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;

/** Probe controls advance only the session clock or fail a named commit; expected state is independent. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(60)
class AuthenticationFailureProbeIT {
    static final String USER="authorization-probe-admin";
    @BeforeAll static void start(){connect();}
    @AfterAll static void stop(){try{clock(0);}finally{close();}}
    static void clock(long offset){query("CALL testprobe.security.clock($offset)",m("offset",offset));}
    static void fault(String operation){query("CALL testprobe.security.failCommit($id)",m("id",operation));}
    @Test @Order(1) void competingBootstrapAfterRollback() throws Exception {
        var request=m("username",USER,"password",PASS);
        try(var s=driver.session();var tx=s.beginTransaction()){tx.run("CALL deposit.utils.bootstrap($r)",m("r",request)).consume();tx.rollback();}
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);var a=pool.submit(()->{start.await();return special("deposit.utils.bootstrap",request);});var b=pool.submit(()->{start.await();return special("deposit.utils.bootstrap",request);});start.countDown();
            int successes=0;for(var task:List.of(a,b))try{task.get(15,TimeUnit.SECONDS);successes++;}catch(ExecutionException e){assertTrue(e.getCause().getMessage().contains("AUTH_FAILED"));}assertEquals(1,successes);
        }
        admin=login(USER,PASS);assertEquals(1,sr(admin));
    }
    @Test @Order(2) void idleAndAbsoluteExpiryUseFixedProductionLimits() {
        String token=login(USER,PASS);clock(1_799_000);sr(token);clock(3_600_001);error("SESSION_EXPIRED",()->call("deposit.get.user",Map.of(),ctx(token)));
        clock(0);String absolute=login(USER,PASS);
        for(long offset=1_200_000;offset<28_800_000;offset+=1_200_000){clock(offset);sr(absolute);}
        clock(28_800_001);error("SESSION_EXPIRED",()->call("deposit.get.user",Map.of(),ctx(absolute)));clock(0);admin=login(USER,PASS);
    }
    @Test @Order(3) void expiryBeforeCommitRollsBackDataRevisionAndReceipt() {
        String token=login(USER,PASS);String namespace=ns(token,"authorization-probe-expiry");var context=write(token,0);
        try(var s=driver.session();var tx=s.beginTransaction()) {
            tx.run("CALL deposit.create.class({name:'MustRollback'},$c)",m("c",context)).consume();clock(28_800_001);
            var failure=assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,tx::commit);assertTrue(failure.getMessage().contains("SESSION_EXPIRED"));
        }finally{clock(0);}
        admin=login(USER,PASS);select(admin,namespace);assertEquals(0,nr(admin));error("NOT_FOUND",()->call("deposit.get.class",m("name","MustRollback"),ctx(admin)));error("NOT_FOUND",()->call("deposit.get.operation",m("operationId",context.get("operationId")),ctx(admin)));
    }
    @Test @Order(4) void failedPasswordCommitDoesNotRevokeOrAdvance() {
        String id=user("user"),name=username(id),token=login(name,PASS);long before=sr(admin);var request=m("userId",id,"newPassword",NEW);var context=write(admin,before);fault((String)context.get("operationId"));
        var failure=assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,()->call("deposit.set.user",request,context));assertTrue(failure.getMessage().contains("AUTH_FAILURE_INJECTED_COMMIT"));assertEquals(before,sr(token));login(name,PASS);
        error("NOT_FOUND",()->call("deposit.get.operation",m("operationId",context.get("operationId"),"scope","server"),ctx(admin)));
        call("deposit.set.user",request,context);authFails(token);login(name,NEW);
    }
    @Test @Order(5) void failedDeletionCommitPreservesSelectionGraphAndReceipt() {
        String namespace=ns(admin,"authorization-probe-delete");String second=login(USER,PASS);select(second,namespace);call("deposit.create.class",m("name","Retained"),write(admin,0));
        var request=deletion(admin);var context=write(admin,sr(admin));long before=sr(admin);fault((String)context.get("operationId"));
        var failure=assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,()->call("deposit.delete.namespace",request,context));assertTrue(failure.getMessage().contains("AUTH_FAILURE_INJECTED_COMMIT"));assertEquals(before,sr(admin));assertEquals(namespace,id(call("deposit.get.activeNamespace",Map.of(),ctx(second))));assertEquals("Retained",call("deposit.get.class",m("name","Retained"),ctx(second)).get("data").get("name").asString());
        error("NOT_FOUND",()->call("deposit.get.operation",m("operationId",context.get("operationId"),"scope","server"),ctx(admin)));call("deposit.delete.namespace",request,context);assertTrue(call("deposit.get.activeNamespace",Map.of(),ctx(second)).get("data").isNull());
    }
    @Test @Order(6) void loginWaitsForPasswordResetCommit() throws Exception {
        String id=user("user"),name=username(id);long before=sr(admin);
        try(var s=driver.session();var tx=s.beginTransaction();var pool=Executors.newSingleThreadExecutor()) {
            tx.run("CALL deposit.set.user($r,$c)",m("r",m("userId",id,"newPassword",NEW),"c",write(admin,before))).consume();var entered=new CountDownLatch(1);
            var attempted=pool.submit(()->{entered.countDown();return login(name,PASS);});assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->attempted.get(200,TimeUnit.MILLISECONDS));tx.commit();
            var failure=assertThrows(ExecutionException.class,()->attempted.get(15,TimeUnit.SECONDS));assertTrue(failure.getCause().getMessage().contains("AUTH_FAILED"));
        }
        login(name,NEW);
    }
    @Test @Order(7) void tokensAreBoundToAuthenticatedNeo4jPrincipal() {
        try(var system=driver.session(SessionConfig.forDatabase("system"))){system.run("CREATE USER authorizationnative SET PASSWORD $p CHANGE NOT REQUIRED",m("p",PASS)).consume();}
        try(var other=GraphDatabase.driver(System.getenv("DEPOSIT_TEST_URI"),AuthTokens.basic("authorizationnative",PASS));var session=other.session()) {
            error("AUTH_FAILED",()->session.run("CALL deposit.get.user(null,$token)",m("token",admin)).consume());
        }finally{try(var system=driver.session(SessionConfig.forDatabase("system"))){system.run("DROP USER authorizationnative").consume();}}
    }
}
