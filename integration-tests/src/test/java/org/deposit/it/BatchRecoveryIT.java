package org.deposit.it;
import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.AuthenticationNamespaceLifecycleIT.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class BatchRecoveryIT {
    @Test void batchReceiptSurvivesRestartAndDoesNotRepeatCommands() throws Exception {
        connect();try {
            var p=new Properties();try(var in=Files.newInputStream(ROOT.resolve("batch-fixture.properties"))){p.load(in);}
            String token=BatchMutationIT.login();assertTrue(call("deposit.get.activeNamespace",Map.of(),ctx(token)).get("data").isNull());BatchMutationIT.select(token,p.getProperty("namespace"));
            var context=write(token,0);context.put("operationId",p.getProperty("operation"));var request=m("commands",List.of(BatchMutationIT.command("tool","deposit.create.userTool",m("name","Recovered","data",BatchMutationIT.TEXT))));
            var receipt=call("deposit.get.operation",m("operationId",p.getProperty("operation")),ctx(token));var replay=call("deposit.apply.batch",request,context);
            assertEquals(receipt.get("data").get("outcome").asMap(),replay.get("data").asMap());assertEquals(BatchMutationIT.TEXT,replay.get("data").get("results").get("tool").get("data").asString());
            assertEquals("current",call("deposit.get.userTool",m("id",p.getProperty("tool")),ctx(token)).get("data").get("data").asString());assertEquals(Long.parseLong(p.getProperty("sequence")),revision(call("deposit.get.activeNamespace",Map.of(),ctx(token))));
        }finally {close();}
    }
}
