package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.Stage1LifecycleIT.*;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

/** OCFL acceptance from public Bolt contracts and independently inspected stored bytes. */
@Timeout(120)
class Stage55CoreIT {
    @BeforeAll static void start(){connect();Stage4CoreIT.token=Stage3CoreIT.login();}
    @AfterAll static void stop(){close();}
    @BeforeEach void fixture(){new Stage4CoreIT().fixture();}
    @Test void immutableObjectIdentityProfileAndStagingSeparation()throws Exception {
        String id=Stage4CoreIT.upload(Stage4CoreIT.ABC,"CON.photo.jpg"),other=Stage4CoreIT.upload(Stage4CoreIT.ABC,"CON.photo.jpg");
        assertNotEquals(id,other);Stage4CoreIT.assertStored(id);Stage4CoreIT.assertStored(other);
        Path root=Stage4CoreIT.objectRoot(id);byte[] inventory=Files.readAllBytes(root.resolve("inventory.json"));
        assertTrue(new String(inventory,java.nio.charset.StandardCharsets.UTF_8).contains(id));
        assertEquals(Stage4CoreIT.hash(inventory)+"  inventory.json",Files.readString(root.resolve("inventory.json.sha256")).trim());
        assertArrayEquals(inventory,Files.readAllBytes(root.resolve("v1/inventory.json")));
        assertEquals("_CON.photo-"+Stage4CoreIT.hash(Stage4CoreIT.ABC)+".jpg",Stage4CoreIT.resourcePath(id).getFileName().toString());
        Stage4CoreIT.awaitStagingCleanup(id);assertTrue(Files.isDirectory(ROOT.resolve("resources/staging")));assertTrue(Files.isDirectory(ROOT.resolve("resources/ocfl-work")));
        assertFalse(Files.exists(ROOT.resolve("resources/live")));assertFalse(Files.exists(ROOT.resolve("resources/deleted")));
        assertTrue(call("deposit.resource.info",m("resourceId",id),ctx(Stage4CoreIT.token)).get("data").get("downloadUrl").isNull());
    }
    @Test void historyAndNamespaceLifecycleNeverRewriteOrMoveObjects()throws Exception {
        Stage5CoreIT.token=Stage4CoreIT.token;Stage5CoreIT.fixture5();Stage5CoreIT.stage4();
        String id=Stage4CoreIT.upload(Stage4CoreIT.ABC,"history-stable.txt");Path root=Stage4CoreIT.objectRoot(id);byte[] before=Files.readAllBytes(root.resolve("inventory.json"));
        Stage4CoreIT.descriptor("F","DResource",m("resourceId",id));String target=Stage5CoreIT.head();
        String removal=Stage5CoreIT.rid(Stage5CoreIT.change("deposit.set.descriptor",m("objectId",Stage5CoreIT.object,"name","F","value",null)));
        String undo=Stage5CoreIT.undo(removal);Stage5CoreIT.change("deposit.utils.redo",m("undoRevisionId",undo));
        Stage5CoreIT.change("deposit.utils.restore",m("targetRevisionId",target));
        Stage5CoreIT.change("deposit.set.versioningType",m("mode","disabled"));
        assertArrayEquals(before,Files.readAllBytes(root.resolve("inventory.json")));Stage4CoreIT.assertStored(id);
        String name=call("deposit.get.activeNamespace",Map.of(),ctx(Stage4CoreIT.token)).get("data").get("name").asString();
        call("deposit.delete.namespace",m("confirmNamespaceName",name,"expectedNamespaceRevision",Stage4CoreIT.seq()),write(Stage4CoreIT.token,revision(call("deposit.get.user",Map.of(),ctx(Stage4CoreIT.token)))));
        assertArrayEquals(before,Files.readAllBytes(root.resolve("inventory.json")));assertArrayEquals(Stage4CoreIT.ABC,Files.readAllBytes(root.resolve("v1/content").resolve("history-stable-"+Stage4CoreIT.hash(Stage4CoreIT.ABC)+".txt")));
    }
    @Test void planExportBackupAndTransferProtectionsResetOrphanAge()throws Exception {
        String id=Stage4CoreIT.upload(Stage4CoreIT.ABC,"protected");Stage4CoreIT.awaitStagingCleanup(id);
        for(String type:List.of("plan","export","backup","transfer")){
            query("MATCH (l:DepositResourceLocation {id:$id}) SET l.leaseUntil=0,l.orphanSince=0 CREATE (:DepositResourceLease {id:$lease,resourceId:$id,type:$type,expires:$expires})",m("id",id,"lease",id+type,"type",type,"expires",System.currentTimeMillis()+86400000));
            assertFalse(candidates().contains(id));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!query("MATCH (l:DepositResourceLocation {id:$id}) RETURN l.orphanSince AS age",m("id",id)).getFirst().get("age").isNull()&&System.nanoTime()<end)Thread.sleep(50);
            assertTrue(query("MATCH (l:DepositResourceLocation {id:$id}) RETURN l.orphanSince AS age",m("id",id)).getFirst().get("age").isNull());
            query("MATCH (l:DepositResourceLease {id:$lease}) DELETE l",m("lease",id+type));
        }
        long after=System.currentTimeMillis()-1000,end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!candidates().contains(id)&&System.nanoTime()<end)Thread.sleep(50);
        assertTrue(candidates().contains(id));assertTrue(query("MATCH (l:DepositResourceLocation {id:$id}) RETURN l.orphanSince AS age",m("id",id)).getFirst().get("age").asLong()>=after);
    }
    static List<Object> candidates(){return call("deposit.admin.pruneResources",m("minimumAgeHours",0L),ctx(Stage4CoreIT.token)).get("data").get("candidateIds").asList();}
    @Test void corruptOrMissingPayloadNeverProducesSuccessfulEmptyRead()throws Exception {
        String id=Stage4CoreIT.upload(Stage4CoreIT.ABC,"corrupt");Stage4CoreIT.awaitStagingCleanup(id);Path payload=Stage4CoreIT.resourcePath(id),inventory=Stage4CoreIT.objectRoot(id).resolve("inventory.json");byte[] bytes=Files.readAllBytes(inventory);
        try{
            Files.delete(payload);error("RESOURCE_CORRUPT",()->call("deposit.resource.downloadChunk",m("resourceId",id,"offset",3L,"length",1L),ctx(Stage4CoreIT.token)));
            Files.write(payload,Stage4CoreIT.ABC);Files.writeString(inventory,"{}");error("RESOURCE_CORRUPT",()->call("deposit.resource.info",m("resourceId",id),ctx(Stage4CoreIT.token)));
        }finally{Files.write(payload,Stage4CoreIT.ABC);Files.write(inventory,bytes);}
        assertEquals("YmM=",call("deposit.resource.downloadChunk",m("resourceId",id,"offset",1L,"length",4L),ctx(Stage4CoreIT.token)).get("data").get("bytesBase64").asString());
    }
    @Test void readTransactionBlocksPruneAndWholeObjectAccountingIsStable()throws Exception {
        String id=Stage4CoreIT.upload(Stage4CoreIT.ABC,"read-prune");Stage4CoreIT.awaitStagingCleanup(id);long expected=Stage4CoreIT.physicalBytes(id);
        try(var session=driver.session();var tx=session.beginTransaction();var pool=Executors.newSingleThreadExecutor()){
            tx.run("CALL deposit.resource.downloadChunk($r,$c)",m("r",m("resourceId",id,"offset",0L,"length",3L),"c",ctx(Stage4CoreIT.token))).consume();
            var entered=new CountDownLatch(1);var future=pool.submit(()->{entered.countDown();query("MATCH (l:DepositResourceLocation {id:$id}) SET l.leaseUntil=0,l.orphanSince=0",m("id",id));return call("deposit.admin.pruneResources",m("dryRun",false,"minimumAgeHours",0L),Stage4CoreIT.op()).get("data");});
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->future.get(250,TimeUnit.MILLISECONDS));tx.commit();String job=future.get(10,TimeUnit.SECONDS).get("pruneId").asString();
            var status=awaitJob(job,Stage4CoreIT.token);assertEquals(expected,status.get("reclaimedBytes").asLong());assertFalse(Files.exists(Stage4CoreIT.objectRoot(id)));Thread.sleep(1100);assertEquals(status,call("deposit.admin.pruneResourcesStatus",m("pruneId",job),ctx(Stage4CoreIT.token)).get("data"));
        }
    }
    static org.neo4j.driver.Value awaitJob(String job,String token)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);org.neo4j.driver.Value status;do{status=call("deposit.admin.pruneResourcesStatus",m("pruneId",job),ctx(token)).get("data");if(status.get("state").asString().equals("completed"))return status;Thread.sleep(50);}while(System.nanoTime()<end);fail("Prune failed: "+status);return null;}
}
