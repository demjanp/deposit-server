package org.deposit.it;
import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.Stage1LifecycleIT.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;

@Timeout(120)
class Stage55ProbeIT {
    @BeforeAll static void start(){connect();Stage4ProbeIT.ensureAccount();Stage4CoreIT.token=Stage3CoreIT.login();}
    @AfterAll static void stop(){close();}
    @BeforeEach void fixture(){new Stage4CoreIT().fixture();}
    @Test void injectedDiskExhaustionRollsBackReadyAndReceiptThenResumes()throws Exception {
        var begin=Stage4CoreIT.op();String upload=call("deposit.resource.beginUpload",Stage4CoreIT.meta(Stage4CoreIT.ABC,"disk-full"),begin).get("data").get("uploadId").asString();
        call("deposit.resource.uploadChunk",Stage4CoreIT.chunk(upload,0,Stage4CoreIT.ABC),Stage4CoreIT.op());var finish=Stage4CoreIT.op();
        String id=query("MATCH (u:DepositTransfer {id:$id}) RETURN u.resourceId AS id",m("id",upload)).getFirst().get("id").asString();
        query("CALL stage0.storage.failNextPublication()",Map.of());error("RESOURCE_CORRUPT",()->call("deposit.resource.finishUpload",m("uploadId",upload),finish));
        assertEquals(0,query("MATCH (r:DResource {id:$id}) RETURN count(r) AS n",m("id",id)).getFirst().get("n").asLong());assertFalse(Files.exists(Stage4CoreIT.objectRoot(id)));
        assertEquals(3,call("deposit.resource.beginUpload",Stage4CoreIT.meta(Stage4CoreIT.ABC,"disk-full"),begin).get("data").get("nextOffset").asLong());
        assertEquals(id,call("deposit.resource.finishUpload",m("uploadId",upload),finish).get("data").get("resourceId").asString());Stage4CoreIT.assertStored(id);
    }
}
