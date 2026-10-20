package org.deposit.it;
import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.AuthenticationNamespaceLifecycleIT.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;

@Timeout(120)
class StorageCapacityProbeIT {
    @BeforeAll static void start(){connect();ResourceFailureProbeIT.ensureAccount();ResourceAndGeometryIT.token=BatchMutationIT.login();}
    @AfterAll static void stop(){close();}
    @BeforeEach void fixture(){new ResourceAndGeometryIT().fixture();}
    @Test void injectedDiskExhaustionRollsBackReadyAndReceiptThenResumes()throws Exception {
        var begin=ResourceAndGeometryIT.op();String upload=call("deposit.resource.beginUpload",ResourceAndGeometryIT.meta(ResourceAndGeometryIT.ABC,"disk-full"),begin).get("data").get("uploadId").asString();
        call("deposit.resource.uploadChunk",ResourceAndGeometryIT.chunk(upload,0,ResourceAndGeometryIT.ABC),ResourceAndGeometryIT.op());var finish=ResourceAndGeometryIT.op();
        String id=query("MATCH (u:DepositTransfer {id:$id}) RETURN u.resourceId AS id",m("id",upload)).getFirst().get("id").asString();
        query("CALL testprobe.storage.failNextPublication()",Map.of());error("RESOURCE_CORRUPT",()->call("deposit.resource.finishUpload",m("uploadId",upload),finish));
        assertEquals(0,query("MATCH (r:DResource {id:$id}) RETURN count(r) AS n",m("id",id)).getFirst().get("n").asLong());assertFalse(Files.exists(ResourceAndGeometryIT.objectRoot(id)));
        assertEquals(3,call("deposit.resource.beginUpload",ResourceAndGeometryIT.meta(ResourceAndGeometryIT.ABC,"disk-full"),begin).get("data").get("nextOffset").asLong());
        assertEquals(id,call("deposit.resource.finishUpload",m("uploadId",upload),finish).get("data").get("resourceId").asString());ResourceAndGeometryIT.assertStored(id);
    }
}
