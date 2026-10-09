package org.deposit.it;
import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.Stage1LifecycleIT.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class Stage3RecoveryIT {
    @Test void batchReceiptSurvivesRestartAndDoesNotRepeatCommands() throws Exception {
        connect();try {
            var p=new Properties();try(var in=Files.newInputStream(ROOT.resolve("stage3-fixture.properties"))){p.load(in);}
            String token=Stage3CoreIT.login();assertTrue(call("deposit.get.activeNamespace",Map.of(),ctx(token)).get("data").isNull());Stage3CoreIT.select(token,p.getProperty("namespace"));
            var context=write(token,0);context.put("operationId",p.getProperty("operation"));var request=m("commands",List.of(Stage3CoreIT.command("tool","deposit.create.userTool",m("name","Recovered","data",Stage3CoreIT.TEXT))));
            var receipt=call("deposit.get.operation",m("operationId",p.getProperty("operation")),ctx(token));var replay=call("deposit.apply.batch",request,context);
            assertEquals(receipt.get("data").get("outcome").asMap(),replay.get("data").asMap());assertEquals(Stage3CoreIT.TEXT,replay.get("data").get("results").get("tool").get("data").asString());
            assertEquals("current",call("deposit.get.userTool",m("id",p.getProperty("tool")),ctx(token)).get("data").get("data").asString());assertEquals(Long.parseLong(p.getProperty("sequence")),revision(call("deposit.get.activeNamespace",Map.of(),ctx(token))));
            Files.writeString(Path.of(System.getenv("STAGE0_EVIDENCE")).resolve("stage3-recovery.txt"),"Batch outcome recovered exactly; current tool unchanged; no duplicate commands or revision.\n");
        }finally {close();}
    }
}
