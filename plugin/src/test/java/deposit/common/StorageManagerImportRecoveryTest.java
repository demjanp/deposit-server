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

    private StorageAdmission.Reservation reservation(String scope,String actor,String operation)throws Exception {
        String owner=ImportWriteBudget.reservationOwnerId(scope,actor,operation);
        return storage.admission.acquire("import-write",owner,operation,"database-result",Map.of(database.databaseLayout().databaseDirectory(),4096L),Json.map("phase","import-write","receiptScope",scope,"namespaceId",scope,"actorId",actor,"operationId",operation));
    }

    private StorageAdmission.Reservation cloneReservation(String ownerKind,String actor,String operation,String targetNamespace)throws Exception {
        String scope="server",owner=ImportWriteBudget.reservationOwnerId(scope,targetNamespace,actor,operation);
        return storage.admission.acquire(ownerKind,owner,operation,"clone-write",Map.of(database.databaseLayout().databaseDirectory(),4096L),Json.map("phase","import-write","receiptScope",scope,"namespaceId",targetNamespace,"resultNamespaceId",targetNamespace,"actorId",actor,"operationId",operation));
    }

    private void writeCloneReceipt(String scope,String actor,String operation,String resultNamespace) {
        writeReceiptRows(scope,actor,operation,resultNamespace,resultNamespace,"clone");
    }

    private void writeReceipt(StorageAdmission.Reservation reservation,String resultMode) {
        writeReceipt(reservation,resultMode,(String)reservation.writeIntent().get("actorId"));
    }
    private void writeReceipt(StorageAdmission.Reservation reservation,String resultMode,String receiptActor) {
        String scope=(String)reservation.writeIntent().get("receiptScope");
        String actual=switch(resultMode) {
            case "missing-result" -> null;
            case "mismatched-result-scope" -> UUID.randomUUID().toString();
            default -> scope;
        };
        writeReceiptRows(scope,receiptActor,reservation.operationId,scope,actual,"apply");
    }

    private void writeReceiptRows(String scope,String actor,String operation,String expected,String actual,String kind) {
        String id=UUID.randomUUID().toString();
        if(actual!=null)storage.importResults.create(new deposit.repository.ImportResultStore.Root(id,actual,
            "server".equals(scope)?"server":"namespace",actor,operation,kind,"COMMITTED",0,null,
            server.sessionTime()+ImportResultContract.RETENTION,null,"{}"),null);
        storage.operational.write(0,db->{
            try(var insert=db.prepareStatement("""
                    INSERT INTO operation_receipts(actor_id,scope,operation_id,request_hash,revision,administrative_mode,
                        import_result_id,result_kind,result_namespace_id,settled_at,id_time) VALUES (?,?,?,'hash',0,0,?,?,?,?, -1)
                    """)) {
                insert.setString(1,actor);
                insert.setString(2,scope);
                insert.setString(3,operation);
                insert.setString(4,id);
                insert.setString(5,kind);
                insert.setString(6,expected);
                insert.setLong(7,server.sessionTime());
                insert.executeUpdate();
            }
            return null;
        });
    }
}
