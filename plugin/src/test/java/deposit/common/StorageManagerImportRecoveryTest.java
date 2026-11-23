package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilderImplementation;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

class StorageManagerImportRecoveryTest {
    @TempDir Path temporary;
    DatabaseManagementService management;
    GraphDatabaseAPI database;
    ServerContext server;
    StorageManager storage;
    String previousConfig;

    @BeforeEach void setup()throws Exception {
        previousConfig=System.getProperty("deposit.config");Path resources=Files.createDirectories(temporary.resolve("resources")),exports=Files.createDirectories(temporary.resolve("exports"));Path config=temporary.resolve("deposit-test.properties");Properties properties=new Properties();properties.setProperty("resourceRoot",resources.toString());properties.setProperty("exportRoot",exports.toString());try(var out=Files.newOutputStream(config)){properties.store(out,"test");}System.setProperty("deposit.config",config.toString());
        management=new DatabaseManagementServiceBuilderImplementation(temporary.resolve("db"),type->type!=DepositExtension.class).build();database=(GraphDatabaseAPI)management.database("neo4j");server=ServerContext.attach(database.databaseId().databaseId().uuid());var field=ServerContext.class.getDeclaredField("database");field.setAccessible(true);field.set(server,database);storage=new StorageManager(server,database);server.storage=storage;var ready=ServerContext.class.getDeclaredMethod("markReadyForTesting");ready.setAccessible(true);ready.invoke(server);
    }
    @AfterEach void close(){if(storage!=null)storage.close();if(management!=null)management.shutdown();if(database!=null)ServerContext.detach(database.databaseId().databaseId().uuid());if(previousConfig==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previousConfig);}

    @Test void startupReleasesOnlyReceiptProvenOrphanedImportReservationsAfterRecovery()throws Exception {
        String scope=UUID.randomUUID().toString(),actor=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();
        var absent=reservation(scope,actor,operation);
        var committed=reservation(UUID.randomUUID().toString(),actor,UUID.randomUUID().toString());writeReceipt(committed,"valid");
        var malformed=reservation(UUID.randomUUID().toString(),actor,UUID.randomUUID().toString());writeReceipt(malformed,"missing-result");
        var mismatchedActor=reservation(UUID.randomUUID().toString(),actor,UUID.randomUUID().toString());writeReceipt(mismatchedActor,"valid",UUID.randomUUID().toString());
        var mismatchedResult=reservation(UUID.randomUUID().toString(),actor,UUID.randomUUID().toString());writeReceipt(mismatchedResult,"mismatched-result-scope");
        String boundScope=UUID.randomUUID().toString(),intentScope=UUID.randomUUID().toString(),boundOperation=UUID.randomUUID().toString();
        var mismatchedScope=storage.admission.acquire("import-write",ImportWriteBudget.reservationOwnerId(boundScope,actor,boundOperation),boundOperation,"database-result",Map.of(database.databaseLayout().databaseDirectory(),4096L),Json.map("phase","import-write","receiptScope",intentScope,"namespaceId",intentScope,"actorId",actor,"operationId",boundOperation));writeReceipt(mismatchedScope,"valid");

        storage.recover();

        assertFalse(Files.exists(storage.files.root.resolve("admission").resolve(absent.id+".json")),"missing receipt is authoritative only after startup recovery completes");
        assertFalse(Files.exists(storage.files.root.resolve("admission").resolve(committed.id+".json")),"a matching committed receipt and result release the owner");
        assertTrue(Files.exists(storage.files.root.resolve("admission").resolve(malformed.id+".json")),"a receipt with a missing result remains protected");
        assertTrue(Files.exists(storage.files.root.resolve("admission").resolve(mismatchedActor.id+".json")),"a receipt for a different actor cannot release the owner");
        assertTrue(Files.exists(storage.files.root.resolve("admission").resolve(mismatchedResult.id+".json")),"a result in another Namespace cannot release the owner");
        assertTrue(Files.exists(storage.files.root.resolve("admission").resolve(mismatchedScope.id+".json")),"a mismatched reservation scope cannot release the owner");
    }

    @Test void runtimeReconciliationPreservesOwnersWithoutACommittedReceipt()throws Exception {
        var uncertain=reservation(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString());
        storage.admission.reconcile(storage::reconcileRuntimeImportWrite);
        assertTrue(Files.exists(storage.files.root.resolve("admission").resolve(uncertain.id+".json")));
        var committed=reservation(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString());writeReceipt(committed,"valid");
        storage.admission.reconcile(storage::reconcileRuntimeImportWrite);
        assertFalse(Files.exists(storage.files.root.resolve("admission").resolve(committed.id+".json")));
    }

    @Test void startupReconcilesCloneImportAndResourceReservationsAgainstServerReceiptAndTargetNamespace()throws Exception {
        String actor=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString(),targetNamespace=UUID.randomUUID().toString();
        var write=cloneReservation("import-write",actor,operation,targetNamespace);writeCloneReceipt("server",actor,operation,targetNamespace);
        String resourceOperation=UUID.randomUUID().toString(),resourceNamespace=UUID.randomUUID().toString();var resource=cloneReservation("import-apply",actor,resourceOperation,resourceNamespace);writeCloneReceipt("server",actor,resourceOperation,resourceNamespace);
        storage.recover();
        assertFalse(Files.exists(storage.files.root.resolve("admission").resolve(write.id+".json")),"clone database/result reservation binds server receipt to the created Namespace");
        assertFalse(Files.exists(storage.files.root.resolve("admission").resolve(resource.id+".json")),"clone resource reservation binds server receipt to its created Namespace");
    }

    @Test void expiryWaitsForUploadOwnerAndRechecksItsRenewedLease()throws Exception {
        String uploadId=UUID.randomUUID().toString(),actor=UUID.randomUUID().toString(),relative="staging/imports/clone-uploads/"+UUID.randomUUID()+".zip";Path staged=storage.files.path(relative);Files.createDirectories(staged.getParent());Files.writeString(staged,"package");
        try(var tx=database.beginTx()){Node upload=tx.createNode(Label.label("DepositCloneUpload"));upload.setProperty("id",uploadId);upload.setProperty("actorId",actor);upload.setProperty("bytes",7L);upload.setProperty("nextOffset",7L);upload.setProperty("sha256",ResourceFiles.checksum(staged));upload.setProperty("state","uploading");upload.setProperty("expires",0L);upload.setProperty("stagingPath",relative);tx.commit();}
        var started=new java.util.concurrent.CountDownLatch(1);var acquired=new java.util.concurrent.CountDownLatch(1);var failed=new java.util.concurrent.atomic.AtomicReference<Throwable>();var observer=ServerContext.class.getDeclaredMethod("storageObserverForTesting",java.util.function.Consumer.class);observer.setAccessible(true);observer.invoke(server,(java.util.function.Consumer<String>)point->{if("clone-upload-expiry-before-lock".equals(point))started.countDown();if("clone-upload-expiry-after-lock".equals(point))acquired.countDown();});
        var owner=database.beginTx();Node upload=owner.findNode(Label.label("DepositCloneUpload"),"id",uploadId);owner.acquireWriteLock(upload);
        Thread maintenance=new Thread(()->{try{storage.recover();}catch(Throwable failure){failed.set(failure);}});maintenance.start();assertTrue(started.await(10,java.util.concurrent.TimeUnit.SECONDS),"expiry candidate reached the shared upload lock boundary");
        upload.setProperty("state","finished");upload.setProperty("expires",System.currentTimeMillis()+60_000L);owner.commit();assertTrue(acquired.await(10,java.util.concurrent.TimeUnit.SECONDS),"maintenance re-acquired the upload record before deciding whether to delete it");maintenance.join(20_000L);observer.invoke(server,(java.util.function.Consumer<String>)point->{});
        assertFalse(maintenance.isAlive(),"maintenance completed after the owner released the upload lock");assertNull(failed.get());assertTrue(Files.exists(staged));
        try(var tx=database.beginTx()){Node retained=tx.findNode(Label.label("DepositCloneUpload"),"id",uploadId);assertNotNull(retained);assertEquals("finished",retained.getProperty("state"));assertTrue(((Number)retained.getProperty("expires")).longValue()>System.currentTimeMillis());tx.rollback();}
    }

    @Test void failedInitialDatabaseBudgetGrowthReleasesEarlierResourceReservationOnRollback()throws Exception {
        var fileStore=Files.getFileStore(storage.files.root);long free=fileStore.getUsableSpace();assertTrue(free>4L*1024*1024);
        var blocker=storage.admission.acquire("test-blocker","fills-capacity",null,"test",Map.of(storage.files.root,free-512L*1024),Map.of());
        String scope=UUID.randomUUID().toString(),actor=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();
        var resourceOwner=storage.admission.acquire("import-apply",ImportWriteBudget.reservationOwnerId(scope,actor,operation),operation,"resource-expansion",Map.of(storage.files.root,0L),Json.map("receiptScope",scope,"actorId",actor,"operationId",operation));
        try(var tx=database.beginTx()){
            var context=new RequestContext(tx,Map.of("token","test-token","operationId",operation));context.namespaceId=scope;context.actorId=actor;
            context.state.release.add(()->{try{storage.admission.close(resourceOwner);}catch(Exception failure){throw new RuntimeException(failure);}});
            assertThrows(ExceptionHandler.DepositException.class,()->ImportWriteBudget.forRequest(context));
            server.rollback(context.state.kernel,new IllegalStateException("test failed budget growth"));tx.rollback();
        } finally {storage.admission.close(blocker);}
        assertFalse(Files.exists(storage.files.root.resolve("admission").resolve(resourceOwner.id+".json")),"rollback closes the earlier resource reservation when database-budget growth fails");
        try(var reservations=Files.list(storage.files.root.resolve("admission"))){assertEquals(0,reservations.count());}
    }

    @Test void failedOrphanDiscoveryKeepsReceiptlessImportReservation()throws Exception {
        var reservation=reservation(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString());
        Path foreign=storage.files.root.resolve("ocfl").resolve("foreign.tmp");Files.writeString(foreign,"unrecognized OCFL content");
        try{assertThrows(IllegalStateException.class,storage::recover);assertTrue(Files.exists(storage.files.root.resolve("admission").resolve(reservation.id+".json")),"startup must not release imports until orphan discovery completes");}
        finally{Files.deleteIfExists(foreign);}
    }

    @Test void startupReclaimsOnlyOwnedHistoryScratchBeforeReleasingOldBaselineReservations()throws Exception{
        Path root=storage.files.root;storage.close();
        var old=new StorageAdmission(root,"previous-process");
        String relative="staging/imports/history-index-owned";Path owned=Files.createDirectories(root.resolve(relative));Files.writeString(owned.resolve(".deposit-history-workspace"),"deposit-history-workspace");Files.writeString(owned.resolve("history.sqlite"),"interrupted scratch");
        var resolved=old.acquire("history-baseline","namespace",UUID.randomUUID().toString(),"history-baseline",Map.of(root,4096L),Json.map("phase","history-baseline","namespaceId","namespace","scratchRoot","staging/imports","scratchPath",relative));
        Path unknown=Files.createDirectories(root.resolve("staging/imports/history-index-unknown"));Files.writeString(unknown.resolve("history.sqlite"),"unattributed");
        var uncertain=old.acquire("history-baseline","other",UUID.randomUUID().toString(),"history-baseline",Map.of(root,4096L),Json.map("phase","history-baseline","namespaceId","other","scratchRoot","staging/imports","scratchPath","staging/imports/history-index-unknown"));
        var malformed=old.acquire("history-baseline","scope",UUID.randomUUID().toString(),"history-baseline",Map.of(root,4096L),Json.map("phase","history-baseline","namespaceId","wrong-scope","scratchRoot","staging/imports"));old.close();
        storage=new StorageManager(server,database);server.storage=storage;
        var active=storage.admission.acquire("history-baseline","active",UUID.randomUUID().toString(),"history-baseline",Map.of(root,0L),Json.map("phase","history-baseline","namespaceId","active","scratchRoot","staging/imports"));
        storage.recover();
        assertFalse(Files.exists(owned));assertFalse(Files.exists(root.resolve("admission").resolve(resolved.id+".json")));
        assertTrue(Files.exists(unknown.resolve("history.sqlite")));for(var kept:java.util.List.of(uncertain,malformed,active))assertTrue(Files.exists(root.resolve("admission").resolve(kept.id+".json")));
    }

    @Test void runtimeKeepsCommittedImportOwnershipUntilHistoryScratchIsRemoved()throws Exception{
        var owner=reservation(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString());writeReceipt(owner,"valid");
        Path scratch=Files.createDirectories(storage.files.path("staging/imports/history-index-pending"));Files.writeString(scratch.resolve(".deposit-history-workspace"),"deposit-history-workspace");Files.writeString(scratch.resolve("history.sqlite"),"scratch");
        var intent=new java.util.LinkedHashMap<>(owner.writeIntent());intent.put("historyScratchPath","staging/imports/history-index-pending");intent.put("scratchCleanupPending",true);storage.admission.setWriteIntent(owner,intent);
        storage.admission.reconcile(storage::reconcileRuntimeImportWrite);assertTrue(Files.exists(storage.files.root.resolve("admission").resolve(owner.id+".json")));
        storage.recover();assertFalse(Files.exists(scratch));assertFalse(Files.exists(storage.files.root.resolve("admission").resolve(owner.id+".json")));
    }

    @Test void restartKeepsAbortedCloneUploadAdmissionUntilExpiryDeletesStaging()throws Exception{
        Path root=storage.files.root;storage.close();var old=new StorageAdmission(root,"previous-process");String uploadId=UUID.randomUUID().toString(),actor=UUID.randomUUID().toString();byte[] payload="partial clone upload".getBytes(java.nio.charset.StandardCharsets.UTF_8);String relative="staging/imports/clone-uploads/"+uploadId+".zip";Path staged=root.resolve(relative);Files.createDirectories(staged.getParent());Files.write(staged,payload);
        var reservation=old.acquire("clone-upload",uploadId,UUID.randomUUID().toString(),"namespace-clone-upload",Map.of(root,0L),Json.map("phase","upload","uploadId",uploadId,"actorId",actor,"bytes",(long)payload.length,"nextOffset",(long)payload.length,"sha256",ResourceFiles.checksum(payload),"stagingPath",relative));
        try(var tx=database.beginTx()){Node upload=tx.createNode(Label.label("DepositCloneUpload"));upload.setProperty("id",uploadId);upload.setProperty("actorId",actor);upload.setProperty("bytes",payload.length);upload.setProperty("nextOffset",payload.length);upload.setProperty("sha256",ResourceFiles.checksum(payload));upload.setProperty("state","aborted");upload.setProperty("expires",System.currentTimeMillis()+60_000L);upload.setProperty("stagingPath",relative);upload.setProperty("admissionId",reservation.id);tx.commit();}
        old.close();storage=new StorageManager(server,database);server.storage=storage;
        storage.recover();
        var resumed=storage.admission.resume(reservation.id);assertEquals(server.runId,resumed.serverRunId());assertTrue(Files.exists(staged),"an aborted upload remains admission-owned until expiry deletes its bytes");
        try(var tx=database.beginTx()){tx.findNode(Label.label("DepositCloneUpload"),"id",uploadId).setProperty("expires",0L);tx.commit();}
        storage.recover();assertFalse(Files.exists(staged));assertFalse(Files.exists(storage.files.root.resolve("admission").resolve(reservation.id+".json")));assertNull(storage.diagnostic);
    }

    @Test void restartKeepsConsumedCloneUploadUntilReceiptBoundExpiryCleanup()throws Exception{
        Path root=storage.files.root;storage.close();var old=new StorageAdmission(root,"previous-process");String uploadId=UUID.randomUUID().toString(),actor=UUID.randomUUID().toString(),cloneOperation=UUID.randomUUID().toString(),resultId=UUID.randomUUID().toString(),namespaceId=UUID.randomUUID().toString();byte[] payload="consumed clone package".getBytes(java.nio.charset.StandardCharsets.UTF_8);String relative="staging/imports/clone-uploads/"+uploadId+".zip";Path staged=root.resolve(relative);Files.createDirectories(staged.getParent());Files.write(staged,payload);
        var reservation=old.acquire("clone-upload",uploadId,UUID.randomUUID().toString(),"namespace-clone-upload",Map.of(root,0L),Json.map("phase","upload","uploadId",uploadId,"actorId",actor,"bytes",(long)payload.length,"nextOffset",(long)payload.length,"sha256",ResourceFiles.checksum(payload),"stagingPath",relative));
        try(var tx=database.beginTx()){Node upload=tx.createNode(Label.label("DepositCloneUpload"));upload.setProperty("id",uploadId);upload.setProperty("actorId",actor);upload.setProperty("bytes",payload.length);upload.setProperty("nextOffset",payload.length);upload.setProperty("sha256",ResourceFiles.checksum(payload));upload.setProperty("state","consumed");upload.setProperty("expires",System.currentTimeMillis()+60_000L);upload.setProperty("stagingPath",relative);upload.setProperty("admissionId",reservation.id);upload.setProperty("consumedOperationId",cloneOperation);upload.setProperty("consumedActorId",actor);upload.setProperty("consumedResultId",resultId);upload.setProperty("consumedNamespaceId",namespaceId);
            Node result=tx.createNode(Label.label("DepositImportResult"));result.setProperty("id",resultId);result.setProperty("namespaceId",namespaceId);result.setProperty("scope","server");result.setProperty("actorId",actor);result.setProperty("operationId",cloneOperation);result.setProperty("kind","clone");
            Node receipt=tx.createNode(Label.label("DepositReceipt"));receipt.setProperty("scope","server");receipt.setProperty("operationId",cloneOperation);receipt.setProperty("actorId",actor);receipt.setProperty("importResultId",resultId);receipt.setProperty("resultNamespaceId",namespaceId);tx.commit();}
        old.close();storage=new StorageManager(server,database);server.storage=storage;
        storage.recover();
        var resumed=storage.admission.resume(reservation.id);assertEquals(server.runId,resumed.serverRunId());assertTrue(Files.exists(staged),"a consumed upload is retained until its package bytes are actually deleted");
        try(var tx=database.beginTx()){tx.findNode(Label.label("DepositCloneUpload"),"id",uploadId).setProperty("expires",0L);tx.commit();}
        storage.recover();assertFalse(Files.exists(staged));assertFalse(Files.exists(storage.files.root.resolve("admission").resolve(reservation.id+".json")));assertNull(storage.diagnostic);
    }

    private StorageAdmission.Reservation reservation(String scope,String actor,String operation)throws Exception {
        String owner=ImportWriteBudget.reservationOwnerId(scope,actor,operation);
        return storage.admission.acquire("import-write",owner,operation,"database-result",Map.of(database.databaseLayout().databaseDirectory(),4096L),Json.map("phase","import-write","receiptScope",scope,"namespaceId",scope,"actorId",actor,"operationId",operation));
    }

    private StorageAdmission.Reservation cloneReservation(String ownerKind,String actor,String operation,String targetNamespace)throws Exception {
        String scope="server",owner=ImportWriteBudget.reservationOwnerId(scope,targetNamespace,actor,operation);
        return storage.admission.acquire(ownerKind,owner,operation,"clone-write",Map.of(database.databaseLayout().databaseDirectory(),4096L),Json.map("phase","import-write","receiptScope",scope,"namespaceId",targetNamespace,"resultNamespaceId",targetNamespace,"actorId",actor,"operationId",operation));
    }

    private void writeCloneReceipt(String scope,String actor,String operation,String resultNamespace) {
        String resultId=UUID.randomUUID().toString();try(var tx=database.beginTx()){
            Node result=tx.createNode(Label.label("DepositImportResult"));result.setProperty("id",resultId);result.setProperty("namespaceId",resultNamespace);result.setProperty("scope","server");result.setProperty("actorId",actor);result.setProperty("operationId",operation);result.setProperty("kind","clone");
            Node receipt=tx.createNode(Label.label("DepositReceipt"));receipt.setProperty("scope",scope);receipt.setProperty("operationId",operation);receipt.setProperty("actorId",actor);receipt.setProperty("importResultId",resultId);receipt.setProperty("resultNamespaceId",resultNamespace);tx.commit();
        }
    }

    private void writeReceipt(StorageAdmission.Reservation reservation,String resultMode) {writeReceipt(reservation,resultMode,(String)reservation.writeIntent().get("actorId"));}
    private void writeReceipt(StorageAdmission.Reservation reservation,String resultMode,String receiptActor) {
        String scope=(String)reservation.writeIntent().get("receiptScope"),operation=reservation.operationId,resultId=UUID.randomUUID().toString();
        try(var tx=database.beginTx()){
            if(!"missing-result".equals(resultMode)){Node result=tx.createNode(Label.label("DepositImportResult"));result.setProperty("id",resultId);result.setProperty("namespaceId","mismatched-result-scope".equals(resultMode)?UUID.randomUUID().toString():scope);result.setProperty("scope","server".equals(scope)?"server":"namespace");result.setProperty("actorId",receiptActor);result.setProperty("operationId",operation);}
            Node receipt=tx.createNode(Label.label("DepositReceipt"));receipt.setProperty("scope",scope);receipt.setProperty("operationId",operation);receipt.setProperty("actorId",receiptActor);receipt.setProperty("importResultId",resultId);
            receipt.setProperty("resultNamespaceId",scope);tx.commit();
        }
    }
}
