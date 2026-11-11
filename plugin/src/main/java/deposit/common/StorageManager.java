package deposit.common;

import deposit.repository.CoreRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.graphdb.Node;

/** Storage lifecycle and maintenance scheduling/recovery coordinator. */
public final class StorageManager implements AutoCloseable {
    public final ResourceFiles files = new ResourceFiles();
    public final ResourceStorage content;
    public final StorageAdmission admission;
    private final ServerContext server;
    private final GraphDatabaseAPI db;
    private final StorageMaintenanceJobs jobs;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "deposit-resource-maintenance"); t.setDaemon(true); return t;
    });
    public volatile String diagnostic;

    public StorageManager(ServerContext server, GraphDatabaseAPI db) {
        this.server = server;
        this.db = db;
        for (Path directory : List.of(db.databaseLayout().databaseDirectory(), db.databaseLayout().getTransactionLogsDirectory())) {
            Path path = directory.toAbsolutePath().normalize();
            for (Path root : List.of(files.root, files.exports))
                if (root.startsWith(path) || path.startsWith(root)) throw new IllegalStateException("Storage overlaps database directories");
        }
        try { content = new OcflResourceStorage(files.root.resolve("ocfl"), files.root.resolve("ocfl-work")); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot initialize physical resource storage", e); }
        try { admission = new StorageAdmission(files.root,server.runId); }
        catch (java.io.IOException e) { content.close();throw new IllegalStateException("Cannot initialize storage admission", e); }
        jobs = new StorageMaintenanceJobs(server, db, files, () -> content, value -> diagnostic = value);
    }

    public void start() { worker.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS); }

    @Override public void close() {
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("Resource maintenance did not stop");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Resource shutdown interrupted", e);
        }
        admission.close();content.close();
    }

    private List<String> ids(String label) {
        try (var tx = db.beginTx()) {
            return new CoreRepository(tx, ZonedDateTime.now(java.time.ZoneOffset.UTC)).nodes(label, Map.of()).stream().map(CoreRepository::id).toList();
        }
    }

    /** Startup reconciliation order is persisted purges, staging expiry, locations, orphans, then backend work. */
    public void recover() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(50);
        try{deposit.repository.HistoryWorkspace.recover(files.path("staging/imports"));}
        catch(java.io.IOException failure){throw new IllegalStateException("Cannot reclaim private history indexes",failure);}
        try { admission.reconcile(this::reconcileReservation); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot reconcile storage reservations", e); }
        for (String id : ids("DepositPruneItem")) { jobs.collect(id); jobs.collect(id); }
        try { jobs.expireImportPlans(); jobs.expireTables(); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot expire staged operational data", e); }
        for (String id : ids("DepositResourceLocation")) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Resource recovery exceeded startup bound");
            jobs.process(id, true);
        }
        jobs.discoverOrphans();
        try { content.recoverWork(); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot reconcile resource work", e); }
        // Import apply publishes immutable OCFL objects before its Neo4j transaction commits.
        // Only after orphan discovery and interrupted OCFL work recovery can a missing receipt
        // prove that a reservation's transaction did not commit.
        try { admission.reconcile(this::reconcileCommittedImportWritesAfterRecovery); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot reconcile recovered import reservations", e); }
    }

    private StorageAdmission.OwnerState reconcileReservation(StorageAdmission.Reservation reservation)throws java.io.IOException {
        if("history-baseline".equals(reservation.ownerKind)){
            Map<String,Object> intent=reservation.writeIntent();
            if(server.runId.equals(reservation.serverRunId())||!"history-baseline".equals(reservation.purpose)
                ||!(intent.keySet().equals(Set.of("phase","namespaceId","scratchRoot"))||intent.keySet().equals(Set.of("phase","namespaceId","scratchRoot","scratchPath")))||!"history-baseline".equals(intent.get("phase"))
                ||!reservation.ownerId.equals(intent.get("namespaceId"))||!"staging/imports".equals(intent.get("scratchRoot")))return StorageAdmission.OwnerState.uncertain();
            // Startup has no admitted request. Scratch is disposable only after its owning process ended.
            deposit.repository.HistoryWorkspace.recover(files.path("staging/imports"));
            if(intent.containsKey("scratchPath")){
                if(!(intent.get("scratchPath") instanceof String relative)||!relative.matches("staging/imports/history-index-[^/\\\\]+"))return StorageAdmission.OwnerState.uncertain();
                Path scratch=files.path(relative);if(Files.exists(scratch,java.nio.file.LinkOption.NOFOLLOW_LINKS))return StorageAdmission.OwnerState.uncertain();
            }
            return StorageAdmission.OwnerState.absent();
        }
        if("maintenance-prune".equals(reservation.ownerKind)){
            try(var tx=db.beginTx()){
                Node item=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC)).find("DepositPruneItem","id",reservation.ownerId);
                if(item==null||Set.of("completed","skipped").contains(item.getProperty("state","")))return StorageAdmission.OwnerState.absent();
                if(Set.of("pending","deleting","failed").contains(item.getProperty("state","")))return new StorageAdmission.OwnerState(true,true,reservation.commitments(),reservation.writeIntent());
                return StorageAdmission.OwnerState.uncertain();
            }
        }
        if("import-apply".equals(reservation.ownerKind)||"import-write".equals(reservation.ownerKind)){
            // Defer this owner until all published resources have been discovered and
            // interrupted OCFL work has been recovered below.
            return StorageAdmission.OwnerState.uncertain();
        }
        if(Set.of("export","tabular-export","namespace-clone").contains(reservation.ownerKind)){
            Object scope=reservation.writeIntent().get("receiptScope");if(!(scope instanceof String receiptScope))return StorageAdmission.OwnerState.uncertain();
            try(var tx=db.beginTx()){
                CoreRepository core=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));
                return core.receipt(receiptScope,reservation.operationId)==null?StorageAdmission.OwnerState.uncertain():StorageAdmission.OwnerState.absent();
            }
        }
        if("import-plan".equals(reservation.ownerKind)){
            try(var tx=db.beginTx()){
                CoreRepository core=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));Node plan=core.find("DepositImportPlan","id",reservation.ownerId);
                if(plan==null)return StorageAdmission.OwnerState.absent();
                if(!"ready".equals(plan.getProperty("state",""))||!plan.hasProperty("stagingPath"))return StorageAdmission.OwnerState.uncertain();
                String staging=(String)plan.getProperty("stagingPath");Path path=files.path(staging);
                if(!Files.isRegularFile(path,java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path))return StorageAdmission.OwnerState.uncertain();
                return new StorageAdmission.OwnerState(true,true,Map.of(),Map.of("stagingPath",staging,"phase","plan-ready"));
            }
        }
        if(!"upload".equals(reservation.ownerKind))return StorageAdmission.OwnerState.uncertain();
        try(var tx=db.beginTx()){
            CoreRepository core=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));Node transfer=core.find("DepositTransfer","id",reservation.ownerId);
            if(transfer==null){Object relative=reservation.writeIntent().get("stagingPath");if(relative instanceof String path&&Files.exists(files.path(path)))return StorageAdmission.OwnerState.uncertain();return StorageAdmission.OwnerState.absent();}
            String state=(String)transfer.getProperty("state",""),resourceId=(String)transfer.getProperty("resourceId"),namespace=(String)transfer.getProperty("namespaceId");
            if(!"uploading".equals(state))return StorageAdmission.OwnerState.absent();
            Node location=core.find("DepositResourceLocation","id",resourceId);if(location==null||!namespace.equals(location.getProperty("namespaceId")))return StorageAdmission.OwnerState.uncertain();
            String staging=(String)location.getProperty("stagingPath");long expected=((Number)transfer.getProperty("bytes")).longValue(),committed=((Number)transfer.getProperty("nextOffset")).longValue();
            if(expected<0||committed<0||committed>expected)return StorageAdmission.OwnerState.uncertain();Path path=files.path(staging);
            if(Files.exists(path)){long actual=Files.size(path);if(actual<committed)throw new java.io.IOException("Committed upload bytes are missing during admission recovery");if(actual>committed)files.truncate(staging,committed);}else if(committed>0)throw new java.io.IOException("Committed upload staging is missing during admission recovery");
            long remaining=expected-committed;Map<String,Long> future=remaining==0?Map.of():Map.of(admission.volumeId(files.root),remaining);
            var intent=Json.map("stagingPath",staging,"expectedBytes",expected,"nextOffset",committed);
            return new StorageAdmission.OwnerState(true,true,future,intent);
        }
    }

    private StorageAdmission.OwnerState reconcileCommittedImportWritesAfterRecovery(StorageAdmission.Reservation reservation)throws java.io.IOException {
        if(!Set.of("import-apply","import-write").contains(reservation.ownerKind))return StorageAdmission.OwnerState.uncertain();
        if(!historyScratchAbsent(reservation))return StorageAdmission.OwnerState.uncertain();
        Object scope=reservation.writeIntent().get("receiptScope");
        if(!(scope instanceof String receiptScope)||reservation.operationId==null)return StorageAdmission.OwnerState.uncertain();
        Object actorId=reservation.writeIntent().get("actorId");
        if(!(actorId instanceof String actor)||!reservationOwnerMatches(reservation,receiptScope,actor))return StorageAdmission.OwnerState.uncertain();
        try(var tx=db.beginTx()){
            CoreRepository core=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));
            Node receipt=core.receipt(receiptScope,reservation.operationId);
            if(receipt==null)return StorageAdmission.OwnerState.absent();
            if(!receiptScope.equals(receipt.getProperty("scope",null))
                ||!reservation.operationId.equals(receipt.getProperty("operationId",null)))
                return StorageAdmission.OwnerState.uncertain();
            if(!actor.equals(receipt.getProperty("actorId",null)))
                return StorageAdmission.OwnerState.uncertain();
            Object resultId=receipt.getProperty("importResultId",null);
            if(!(resultId instanceof String id))return StorageAdmission.OwnerState.uncertain();
            Node result=core.find("DepositImportResult","id",id);
            if(result==null||!receiptScope.equals(result.getProperty("namespaceId",null))
                ||!reservation.operationId.equals(result.getProperty("operationId",null)))
                return StorageAdmission.OwnerState.uncertain();
            Map<String,Object> outcome=Json.read((String)receipt.getProperty("outcome"));
            if(!id.equals(outcome.get("resultId"))
                ||!deposit.common.ImportResultContract.FORMAT.equals(outcome.get("format")))
                return StorageAdmission.OwnerState.uncertain();
            return StorageAdmission.OwnerState.absent();
        } catch(RuntimeException invalidReceipt) {
            return StorageAdmission.OwnerState.uncertain();
        }
    }

    private static boolean reservationOwnerMatches(StorageAdmission.Reservation reservation,String scope,String actor) {
        return ImportWriteBudget.reservationOwnerId(scope,actor,reservation.operationId).equals(reservation.ownerId);
    }

    StorageAdmission.OwnerState reconcileRuntimeImportWrite(StorageAdmission.Reservation reservation)throws java.io.IOException {
        if(!Set.of("import-apply","import-write").contains(reservation.ownerKind))return StorageAdmission.OwnerState.uncertain();
        if(Boolean.TRUE.equals(reservation.writeIntent().get("scratchCleanupPending")))return StorageAdmission.OwnerState.uncertain();
        if(!historyScratchAbsent(reservation))return StorageAdmission.OwnerState.uncertain();
        Object scope=reservation.writeIntent().get("receiptScope"),actor=reservation.writeIntent().get("actorId");
        if(!(scope instanceof String receiptScope)||!(actor instanceof String actorId)||reservation.operationId==null
            ||!reservationOwnerMatches(reservation,receiptScope,actorId))return StorageAdmission.OwnerState.uncertain();
        try(var tx=db.beginTx()){
            CoreRepository core=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));Node receipt=core.receipt(receiptScope,reservation.operationId);
            if(receipt==null)return StorageAdmission.OwnerState.uncertain();
            if(!receiptScope.equals(receipt.getProperty("scope",null))||!reservation.operationId.equals(receipt.getProperty("operationId",null))||!actorId.equals(receipt.getProperty("actorId",null)))return StorageAdmission.OwnerState.uncertain();
            Object resultId=receipt.getProperty("importResultId",null);if(!(resultId instanceof String id))return StorageAdmission.OwnerState.uncertain();
            Node result=core.find("DepositImportResult","id",id);if(result==null||!receiptScope.equals(result.getProperty("namespaceId",null))||!reservation.operationId.equals(result.getProperty("operationId",null)))return StorageAdmission.OwnerState.uncertain();
            Map<String,Object> outcome=Json.read((String)receipt.getProperty("outcome"));if(!id.equals(outcome.get("resultId"))||!ImportResultContract.FORMAT.equals(outcome.get("format")))return StorageAdmission.OwnerState.uncertain();
            return StorageAdmission.OwnerState.absent();
        } catch(RuntimeException invalidReceipt){return StorageAdmission.OwnerState.uncertain();}
    }

    private boolean historyScratchAbsent(StorageAdmission.Reservation reservation)throws java.io.IOException{
        if(!reservation.writeIntent().containsKey("historyScratchPath"))return true;
        Object raw=reservation.writeIntent().get("historyScratchPath");
        if(!(raw instanceof String relative)||!relative.matches("staging/imports/history-index-[^/\\\\]+"))return false;
        return !Files.exists(files.path(relative),java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    private void tick() {
        try {
            jobs.expireImportPlans(); jobs.expireTables();
            for (String id : ids("DepositResourceLocation")) {
                if (Thread.currentThread().isInterrupted()) return;
                jobs.process(id, false);
            }
            for (String id : ids("DepositPruneItem")) jobs.collect(id);
            // Runtime cleanup is safe only when a durable receipt proves the import transaction committed.
            admission.reconcile(this::reconcileRuntimeImportWrite);
        } catch (Exception e) { diagnostic = "Resource maintenance pending: " + e.getClass().getSimpleName(); }
    }
}
