package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;
import static org.deposit.it.AuthenticationNamespaceLifecycleIT.*;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

/** OCFL acceptance from public Bolt contracts and independently inspected stored bytes. */
@Timeout(120)
class ImmutableResourceStorageIT {
    @BeforeAll static void start(){connect();ensureAccount();ResourceAndGeometryIT.token=BatchMutationIT.login();}
    @AfterAll static void stop(){close();}
    @BeforeEach void fixture(){new ResourceAndGeometryIT().fixture();}
    @Test void immutableObjectIdentityProfileAndStagingSeparation()throws Exception {
        String id=ResourceAndGeometryIT.upload(ResourceAndGeometryIT.ABC,"CON.photo.jpg"),other=ResourceAndGeometryIT.upload(ResourceAndGeometryIT.ABC,"CON.photo.jpg");
        assertNotEquals(id,other);ResourceAndGeometryIT.assertStored(id);ResourceAndGeometryIT.assertStored(other);
        Path root=ResourceAndGeometryIT.objectRoot(id);byte[] inventory=Files.readAllBytes(root.resolve("inventory.json"));
        assertTrue(new String(inventory,java.nio.charset.StandardCharsets.UTF_8).contains(id));
        assertEquals(ResourceAndGeometryIT.hash(inventory)+"  inventory.json",Files.readString(root.resolve("inventory.json.sha256")).trim());
        assertArrayEquals(inventory,Files.readAllBytes(root.resolve("v1/inventory.json")));
        assertEquals("_CON.photo-"+ResourceAndGeometryIT.hash(ResourceAndGeometryIT.ABC)+".jpg",ResourceAndGeometryIT.resourcePath(id).getFileName().toString());
        ResourceAndGeometryIT.awaitStagingCleanup(id);assertTrue(Files.isDirectory(ROOT.resolve("resources/staging")));assertTrue(Files.isDirectory(ROOT.resolve("resources/ocfl-work")));
        assertFalse(Files.exists(ROOT.resolve("resources/live")));assertFalse(Files.exists(ROOT.resolve("resources/deleted")));
        assertEquals(System.getenv("DEPOSIT_TEST_HTTP_BASE")+"/resources/"+ResourceAndGeometryIT.ns+"/"+id,call("deposit.resource.info",m("resourceId",id),ctx(ResourceAndGeometryIT.token)).get("data").get("downloadUrl").asString());
    }
    @Test void historyAndNamespaceLifecycleNeverRewriteOrMoveObjects()throws Exception {
        RevisionHistoryIT.token=ResourceAndGeometryIT.token;RevisionHistoryIT.fixture5();RevisionHistoryIT.resource();
        String id=ResourceAndGeometryIT.upload(ResourceAndGeometryIT.ABC,"history-stable.txt");Path root=ResourceAndGeometryIT.objectRoot(id);byte[] before=Files.readAllBytes(root.resolve("inventory.json"));
        ResourceAndGeometryIT.descriptor("F","DResource",m("resourceId",id));String target=RevisionHistoryIT.head();
        String removal=RevisionHistoryIT.rid(RevisionHistoryIT.change("deposit.set.descriptor",m("objectId",RevisionHistoryIT.object,"name","F","value",null)));
        String undo=RevisionHistoryIT.undo(removal);RevisionHistoryIT.change("deposit.utils.redo",m("undoRevisionId",undo));
        RevisionHistoryIT.change("deposit.utils.restore",m("targetRevisionId",target));
        RevisionHistoryIT.change("deposit.set.versioningType",m("mode","disabled"));
        assertArrayEquals(before,Files.readAllBytes(root.resolve("inventory.json")));ResourceAndGeometryIT.assertStored(id);
        String name=call("deposit.get.activeNamespace",Map.of(),ctx(ResourceAndGeometryIT.token)).get("data").get("name").asString();
        call("deposit.delete.namespace",m("confirmNamespaceName",name,"expectedNamespaceRevision",ResourceAndGeometryIT.seq()),write(ResourceAndGeometryIT.token,revision(call("deposit.get.user",Map.of(),ctx(ResourceAndGeometryIT.token)))));
        assertArrayEquals(before,Files.readAllBytes(root.resolve("inventory.json")));assertArrayEquals(ResourceAndGeometryIT.ABC,Files.readAllBytes(root.resolve("v1/content").resolve("history-stable-"+ResourceAndGeometryIT.hash(ResourceAndGeometryIT.ABC)+".txt")));
    }
    @Test void planExportBackupAndTransferProtectionsResetOrphanAge()throws Exception {
        String id=ResourceAndGeometryIT.upload(ResourceAndGeometryIT.ABC,"protected");ResourceAndGeometryIT.awaitStagingCleanup(id);
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
    static List<Object> candidates(){return call("deposit.admin.pruneResources",m("minimumAgeHours",0L),ctx(ResourceAndGeometryIT.token)).get("data").get("candidateIds").asList();}
    @Test void corruptOrMissingPayloadNeverProducesSuccessfulEmptyRead()throws Exception {
        String id=ResourceAndGeometryIT.upload(ResourceAndGeometryIT.ABC,"corrupt");ResourceAndGeometryIT.awaitStagingCleanup(id);Path payload=ResourceAndGeometryIT.resourcePath(id),inventory=ResourceAndGeometryIT.objectRoot(id).resolve("inventory.json");byte[] bytes=Files.readAllBytes(inventory);
        try{
            Files.delete(payload);error("RESOURCE_CORRUPT",()->call("deposit.resource.downloadChunk",m("resourceId",id,"offset",3L,"length",1L),ctx(ResourceAndGeometryIT.token)));
            Files.write(payload,ResourceAndGeometryIT.ABC);Files.writeString(inventory,"{}");error("RESOURCE_CORRUPT",()->call("deposit.resource.info",m("resourceId",id),ctx(ResourceAndGeometryIT.token)));
        }finally{Files.write(payload,ResourceAndGeometryIT.ABC);Files.write(inventory,bytes);}
        assertEquals("YmM=",call("deposit.resource.downloadChunk",m("resourceId",id,"offset",1L,"length",4L),ctx(ResourceAndGeometryIT.token)).get("data").get("bytesBase64").asString());
    }
    @Test void readTransactionBlocksPruneAndWholeObjectAccountingIsStable()throws Exception {
        String id=ResourceAndGeometryIT.upload(ResourceAndGeometryIT.ABC,"read-prune");ResourceAndGeometryIT.awaitStagingCleanup(id);long expected=ResourceAndGeometryIT.physicalBytes(id);
        try(var session=driver.session();var tx=session.beginTransaction();var pool=Executors.newSingleThreadExecutor()){
            tx.run("CALL deposit.resource.downloadChunk($r,$c)",m("r",m("resourceId",id,"offset",0L,"length",3L),"c",ctx(ResourceAndGeometryIT.token))).consume();
            var entered=new CountDownLatch(1);var future=pool.submit(()->{entered.countDown();query("MATCH (l:DepositResourceLocation {id:$id}) SET l.leaseUntil=0,l.orphanSince=0",m("id",id));return call("deposit.admin.pruneResources",m("dryRun",false,"minimumAgeHours",0L),ResourceAndGeometryIT.op()).get("data");});
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->future.get(250,TimeUnit.MILLISECONDS));tx.commit();String job=future.get(10,TimeUnit.SECONDS).get("pruneId").asString();
            var status=awaitJob(job,ResourceAndGeometryIT.token);assertEquals(expected,status.get("reclaimedBytes").asLong());assertFalse(Files.exists(ResourceAndGeometryIT.objectRoot(id)));Thread.sleep(1100);assertEquals(status,call("deposit.admin.pruneResourcesStatus",m("pruneId",job),ctx(ResourceAndGeometryIT.token)).get("data"));
        }
    }
    static org.neo4j.driver.Value awaitJob(String job,String token)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);org.neo4j.driver.Value status;do{status=call("deposit.admin.pruneResourcesStatus",m("pruneId",job),ctx(token)).get("data");if(status.get("state").asString().equals("completed"))return status;Thread.sleep(50);}while(System.nanoTime()<end);fail("Prune failed: "+status);return null;}
}
