package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.Stage1LifecycleIT.*;
import java.util.*;
import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.*;

/** Executed after actual graceful restart and process termination by the external supervisor. */
class Stage1RecoveryIT {
    @Test void reauthenticateRecoverReceiptsAndRetainTypedGraph() throws Exception {
        Stage1LifecycleIT.connect();
        try {
            var fixture=new Properties();try(var input=Files.newInputStream(ROOT.resolve("stage1-fixture.properties"))){fixture.load(input);}
            String oldToken=Files.readString(ROOT.resolve("secrets/stage1-session.txt"));error("AUTH_FAILED",()->call("deposit.get.user",Map.of(),ctx(oldToken)));
            var login=special("deposit.utils.login",m("username",ACCOUNT,"password",PASSWORD));String token=login.get("value").asString();assertEquals(0,revision(login));
            assertTrue(call("deposit.get.activeNamespace",Map.of(),ctx(token)).get("data").isNull());
            var serverContext=write(token,Long.parseLong(fixture.getProperty("serverExpected")));serverContext.put("operationId",fixture.getProperty("serverOperation"));
            var serverReplay=call("deposit.create.namespace",m("name",fixture.getProperty("namespaceName"),"historyMode","disabled"),serverContext);assertEquals(fixture.getProperty("namespace"),id(serverReplay));assertEquals(0,revision(serverReplay));
            error("REVISION_CONFLICT",()->call("deposit.get.namespacePage",m("pageSize",1L,"cursor",fixture.getProperty("serverCursor")),ctx(token)));
            call("deposit.set.activeNamespace",m("id",fixture.getProperty("namespace")),ctx(token));
            long expected=Long.parseLong(fixture.getProperty("sequence"));assertEquals(expected,revision(call("deposit.get.activeNamespace",Map.of(),ctx(token))));
            var request=m("classId",fixture.getProperty("Specimen"),"descriptors",m("Mass",33L));
            var context=write(token,Long.parseLong(fixture.getProperty("expectedRevision")));context.put("operationId",fixture.getProperty("operation"));
            var replay=call("deposit.create.object",request,context);assertTrue(replay.get("node").isNull());assertEquals(fixture.getProperty("receiptObject"),id(replay));assertEquals(33,replay.get("data").get("descriptors").get("Mass").asInt());
            assertEquals(expected,revision(call("deposit.get.activeNamespace",Map.of(),ctx(token))));
            assertEquals(7,call("deposit.get.objectPage",m("classId",fixture.getProperty("bulk"),"pageSize",1000L,"cursor",fixture.getProperty("namespaceCursor")),ctx(token)).get("data").get("items").size());
            assertEquals(1007,query("CALL deposit.get.object($r,$c) YIELD node RETURN count(node) AS n",m("r",m("classId",fixture.getProperty("bulk")),"c",ctx(token))).getFirst().get("n").asInt());
            assertEquals(30,query("CALL deposit.get.object($r,$c) YIELD node MATCH (node)-[:HAS_DESCRIPTOR]->(d:Descriptor {name:'Mass'}) RETURN sum(d.value) AS total",m("r",m("classId",fixture.getProperty("Specimen")),"c",ctx(token))).getFirst().get("total").asInt());
            // Persist this actual session for the next process-termination check; never copy it to evidence.
            Files.writeString(ROOT.resolve("secrets/stage1-session.txt"),token);
            Files.writeString(Path.of(System.getenv("STAGE0_EVIDENCE")).resolve("stage1-recovery.txt"),"Namespace "+fixture.getProperty("namespace")+" sequence "+expected+"; old session rejected; server revision reset; receipt unchanged; 1007 bulk Objects retained.\n");
        } finally {Stage1LifecycleIT.close();}
    }
}
