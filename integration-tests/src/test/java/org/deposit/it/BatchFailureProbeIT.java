package org.deposit.it;
import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.AuthenticationNamespaceLifecycleIT.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Runs after AuthenticationFailureProbeIT on the same isolated probe distribution. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BatchFailureProbeIT {
    static String token,ns;
    @BeforeAll static void setup() {connect();token=special("deposit.utils.login",m("username",AuthenticationFailureProbeIT.USER,"password",AccountAuthorizationIT.PASS)).get("value").asString();ns=id(call("deposit.create.namespace",m("name","Batch-probe","historyMode","disabled"),write(token,revision(call("deposit.get.user",Map.of(),ctx(token))))));BatchMutationIT.select(token,ns);}
    @AfterAll static void stop() {try {AuthenticationFailureProbeIT.clock(0);}finally{close();}}
    @Test @Order(1) void failedBatchCommitRollsBackAllCategoriesAndReceiptThenRetries() {
        var context=write(token,0);var commands=new ArrayList<>(BatchMutationIT.fixture());commands.add(BatchMutationIT.command("tool","deposit.create.userTool",m("name","Exact","data",BatchMutationIT.TEXT)));var request=m("commands",commands);
        AuthenticationFailureProbeIT.fault((String)context.get("operationId"));var failure=assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,()->call("deposit.apply.batch",request,context));assertTrue(failure.getMessage().contains("AUTH_FAILURE_INJECTED_COMMIT"));
        assertEquals(0,revision(call("deposit.get.activeNamespace",Map.of(),ctx(token))));assertEquals(0,query("MATCH (n:DepositLive {namespaceId:$ns}) RETURN count(n) AS n",m("ns",ns)).getFirst().get("n").asInt());error("NOT_FOUND",()->call("deposit.get.operation",m("operationId",context.get("operationId")),ctx(token)));
        var result=call("deposit.apply.batch",request,context);assertEquals(1,revision(result));assertEquals(result.get("data").asMap(),call("deposit.apply.batch",request,context).get("data").asMap());
    }
    @Test @Order(2) void expiryAfterBatchReturnPreventsCommit() {
        var context=write(token,1);try(var s=driver.session();var tx=s.beginTransaction()) {
            tx.run("CALL deposit.apply.batch($r,$c)",m("r",m("commands",List.of(BatchMutationIT.command("tool","deposit.create.userTool",m("name","Expired","data","x")))),"c",context)).consume();AuthenticationFailureProbeIT.clock(28_800_001);
            var failure=assertThrows(org.neo4j.driver.exceptions.Neo4jException.class,tx::commit);assertTrue(failure.getMessage().contains("SESSION_EXPIRED"));
        }finally {AuthenticationFailureProbeIT.clock(0);}
        token=special("deposit.utils.login",m("username",AuthenticationFailureProbeIT.USER,"password",AccountAuthorizationIT.PASS)).get("value").asString();BatchMutationIT.select(token,ns);assertEquals(1,revision(call("deposit.get.activeNamespace",Map.of(),ctx(token))));error("NOT_FOUND",()->call("deposit.get.userTool",m("name","Expired"),ctx(token)));error("NOT_FOUND",()->call("deposit.get.operation",m("operationId",context.get("operationId")),ctx(token)));
    }
}
