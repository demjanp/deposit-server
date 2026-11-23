package deposit.common;

import deposit.repository.CoreRepository;
import deposit.repository.ResourceRepository;
import deposit.repository.OperationAttemptRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final OperationMaintenance operations;
    private final StorageMaintenanceJobs jobs;
    private final ScheduledExecutorService worker = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "deposit-resource-maintenance"); t.setDaemon(true); return t;
    });
    public volatile String diagnostic;

    public StorageManager(ServerContext server, GraphDatabaseAPI db) {
        operations = new OperationMaintenance(server, db);
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

    public void start() {
        worker.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
        worker.scheduleWithFixedDelay(server::checkRequestLiveness, 1, 1, TimeUnit.SECONDS);
    }

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
        operations.recover();
        try { admission.reconcile(this::reconcileReservation); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot reconcile storage reservations", e); }
        for (String id : ids("DepositPruneItem")) { jobs.collect(id); jobs.collect(id); }
        try { jobs.expireImportPlans(); jobs.expireImportResults(); jobs.expireTables(); jobs.expireExchange(); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot expire staged operational data", e); }
        try { content.recoverWork(); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot reconcile resource work", e); }
        for (String id : ids("DepositResourceLocation")) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Resource recovery exceeded startup bound");
            jobs.process(id, true);
        }
        try { admission.reconcile(this::reconcileOutputIntent); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot settle OCFL write intents", e); }
        operations.finishCleanup();
        jobs.discoverOrphans();
        // Import apply publishes immutable OCFL objects before its Neo4j transaction commits.
        // Only after orphan discovery and interrupted OCFL work recovery can a missing receipt
        // prove that a reservation's transaction did not commit.
        try { admission.reconcile(this::reconcileCommittedImportWritesAfterRecovery); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot reconcile recovered import reservations", e); }
    }

    private StorageAdmission.OwnerState reconcileOutputIntent(StorageAdmission.Reservation reservation)throws java.io.IOException {
        return switch(reservation.ownerKind){
            case "ocfl-publication" -> reconcilePublication(reservation);
            case "tabular-load" -> reconcileTableStage(reservation);
            case "import-plan" -> reconcilePackageStage(reservation);
            case "export-scratch" -> reconcileExportScratch(reservation);
            case "exchange-stage" -> reconcileExchangeStage(reservation);
            default -> StorageAdmission.OwnerState.uncertain();
        };
    }

    private StorageAdmission.OwnerState reconcileExchangeStage(StorageAdmission.Reservation reservation)throws java.io.IOException {
        var intent=reservation.writeIntent();
        String path="staging/exchange/"+RequestContext.uuid(reservation.ownerId);
        if(!"exchange-stage".equals(intent.get("phase")) || !path.equals(intent.get("stagingPath"))
                || !(intent.get("actorId") instanceof String actor) || !(intent.get("receiptScope") instanceof String scope)
                || reservation.operationId==null)return StorageAdmission.OwnerState.uncertain();
        try(var tx=db.beginTx()){
            if(tx.findNode(org.neo4j.graphdb.Label.label("DepositExchangeStage"),"id",reservation.ownerId)!=null)
                return StorageAdmission.OwnerState.uncertain();
            Node attempt=new OperationAttemptRepository(tx).find(actor,scope,reservation.operationId);
            if(attempt==null||OperationAttemptRepository.open(OperationAttemptRepository.state(attempt)))
                return StorageAdmission.OwnerState.uncertain();
            files.deleteOwnedStagingTree(path);
            return StorageAdmission.OwnerState.absent();
        }
    }

    private StorageAdmission.OwnerState reconcileExportScratch(StorageAdmission.Reservation reservation)throws java.io.IOException {
        var intent=reservation.writeIntent();
        if(!"export-scratch".equals(intent.get("phase")) || !(intent.get("actorId") instanceof String actor)
                || !(intent.get("receiptScope") instanceof String scope) || reservation.operationId==null
                || !reservation.ownerId.equals(RequestContext.uuid(reservation.ownerId)))
            return StorageAdmission.OwnerState.uncertain();
        try(var tx=db.beginTx()){
            Node attempt=new OperationAttemptRepository(tx).find(actor,scope,reservation.operationId);
            if(attempt==null||OperationAttemptRepository.open(OperationAttemptRepository.state(attempt)))return StorageAdmission.OwnerState.uncertain();
            files.deleteOwnedStagingTree("staging/exports/attempt-"+reservation.ownerId);
            return StorageAdmission.OwnerState.absent();
        }
    }

    private StorageAdmission.OwnerState reconcilePackageStage(StorageAdmission.Reservation reservation)throws java.io.IOException {
        var intent=reservation.writeIntent();
        if(!Set.of("package-copy","package-create").contains(intent.getOrDefault("phase",""))
                || !(intent.get("actorId") instanceof String actor) || !(intent.get("receiptScope") instanceof String scope)
                || !(intent.get("stagingPath") instanceof String path) || reservation.operationId==null
                || !path.matches("staging/imports/(rows-)?[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.zip"))
            return StorageAdmission.OwnerState.uncertain();
        try(var tx=db.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));
            Node attempt=new OperationAttemptRepository(tx).find(actor,scope,reservation.operationId);
            if(attempt==null||OperationAttemptRepository.open(OperationAttemptRepository.state(attempt)))return StorageAdmission.OwnerState.uncertain();
            try(var plans=core.scanNodes("DepositImportPlan",Map.of("stagingPath",path))){if(plans.hasNext())return StorageAdmission.OwnerState.uncertain();}
            Files.deleteIfExists(files.path(path));
            return StorageAdmission.OwnerState.absent();
        }
    }

    private StorageAdmission.OwnerState reconcileTableStage(StorageAdmission.Reservation reservation)throws java.io.IOException {
        var intent=reservation.writeIntent();
        if(!"table-load".equals(intent.get("phase")) || !(intent.get("actorId") instanceof String actor)
                || !(intent.get("receiptScope") instanceof String scope) || reservation.operationId==null
                || !reservation.ownerId.equals(RequestContext.uuid(reservation.ownerId)))
            return StorageAdmission.OwnerState.uncertain();
        String base="staging/tables/"+reservation.ownerId;
        try(var tx=db.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));
            Node attempt=new OperationAttemptRepository(tx).find(actor,scope,reservation.operationId);
            if(attempt==null)return StorageAdmission.OwnerState.uncertain();
            String state=OperationAttemptRepository.state(attempt);
            if(OperationAttemptRepository.open(state))return StorageAdmission.OwnerState.uncertain();
            Node table=core.find("DepositTabularTable","id",reservation.ownerId);
            if(state.equals("COMPLETED_NO_DOMAIN_WRITE")){
                if(table==null||!actor.equals(table.getProperty("creatorId",null))||!scope.equals(table.getProperty("namespaceId",null))
                        || !base.concat(".json").equals(table.getProperty("path",null))
                        || !base.concat(".idx").equals(table.getProperty("rowIndexPath",null)))
                    return StorageAdmission.OwnerState.uncertain();
            }else if(Set.of("CANCELLED_ROLLED_BACK","FAILED_ROLLED_BACK").contains(state)&&table==null){
                Files.deleteIfExists(files.path(base+".json"));
                Files.deleteIfExists(files.path(base+".idx"));
            }else return StorageAdmission.OwnerState.uncertain();
            Files.deleteIfExists(files.path(base+".source"));
            String workspace=base+".json.work";
            Files.deleteIfExists(files.path(workspace+"/shared-strings.dat"));
            Files.deleteIfExists(files.path(workspace+"/shared-strings.idx"));
            Files.deleteIfExists(files.path(workspace));
            return StorageAdmission.OwnerState.absent();
        }
    }

    private StorageAdmission.OwnerState reconcilePublication(StorageAdmission.Reservation reservation)throws java.io.IOException {
        if(!"ocfl-publication".equals(reservation.ownerKind))return StorageAdmission.OwnerState.uncertain();
        var intent=reservation.writeIntent();
        if(!reservation.ownerId.equals(intent.get("resourceId")) || !"publish".equals(intent.get("phase"))
                || !(intent.get("receiptScope") instanceof String scope) || !(intent.get("actorId") instanceof String actor)
                || !(intent.get("storedFilename") instanceof String filename) || !(intent.get("checksum") instanceof String checksum)
                || !(intent.get("bytes") instanceof Number size) || reservation.operationId==null)
            return StorageAdmission.OwnerState.uncertain();
        String resource=reservation.ownerId;
        try(var tx=db.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));
            Node attempt=new OperationAttemptRepository(tx).find(actor,scope,reservation.operationId);
            if(attempt==null)return StorageAdmission.OwnerState.uncertain();
            String status=OperationAttemptRepository.state(attempt);
            Node location=core.find("DepositResourceLocation","id",resource);
            if(Set.of("COMMITTED","COMPLETED_NO_DOMAIN_WRITE").contains(status)){
                if(location==null || !filename.equals(location.getProperty("storedFilename",null))
                        || !checksum.equals(location.getProperty("checksum",null))
                        || size.longValue()!=ResourceRepository.number(location,"bytes",-1))
                    return StorageAdmission.OwnerState.uncertain();
                return StorageAdmission.OwnerState.absent();
            }
            if(!Set.of("FAILED_ROLLED_BACK","CANCELLED_ROLLED_BACK").contains(status))return StorageAdmission.OwnerState.uncertain();
            if(core.find("DResource","id",resource)!=null)return StorageAdmission.OwnerState.uncertain();
            if(!content.exists(resource))return StorageAdmission.OwnerState.absent();
            // Pre-existing upload locations are reclaimed under the corresponding writer
            // and resource locks. Only an artifact with no graph identity is purged here.
            if(location!=null)return StorageAdmission.OwnerState.uncertain();
            var expected=new ResourceStorage.Content(resource,filename,size.longValue(),checksum);
            try{content.verify(expected);}
            catch(java.io.IOException corruption){
                diagnostic="Quarantined OCFL write intent failed fixity verification";
                attempt.setProperty("cleanupState","failed");
                tx.commit();
                return StorageAdmission.OwnerState.uncertain();
            }
            content.purge(resource);
            return StorageAdmission.OwnerState.absent();
        }
    }

    private StorageAdmission.OwnerState reconcileReservation(StorageAdmission.Reservation reservation)throws java.io.IOException {
        if("exchange-write".equals(reservation.ownerKind)&&!server.runId.equals(reservation.serverRunId()))
            return StorageAdmission.OwnerState.absent();
        if("exchange-stage".equals(reservation.ownerKind))return reconcileExchangeStage(reservation);
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
        if("tabular-export".equals(reservation.ownerKind)){
            var intent=reservation.writeIntent();
            if(!(intent.get("receiptScope") instanceof String scope) || !(intent.get("actorId") instanceof String actor)
                    || reservation.operationId==null)return StorageAdmission.OwnerState.uncertain();
            try(var tx=db.beginTx()){
                CoreRepository core=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));
                return core.receipt(actor,scope,reservation.operationId)==null
                    ?StorageAdmission.OwnerState.uncertain():StorageAdmission.OwnerState.absent();
            }
        }
        if("import-plan".equals(reservation.ownerKind)){
            try(var tx=db.beginTx()){
                CoreRepository core=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));Node plan=core.find("DepositImportPlan","id",reservation.ownerId);
                if(plan==null)return reservation.writeIntent().containsKey("stagingPath")
                    ?reconcilePackageStage(reservation):StorageAdmission.OwnerState.absent();
                if(!"ready".equals(plan.getProperty("state",""))||!plan.hasProperty("stagingPath"))return StorageAdmission.OwnerState.uncertain();
                String staging=(String)plan.getProperty("stagingPath");Path path=files.path(staging);
                if(!Files.isRegularFile(path,java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path))return StorageAdmission.OwnerState.uncertain();
                return new StorageAdmission.OwnerState(true,true,Map.of(),Map.of("stagingPath",staging,"phase","plan-ready"));
            }
        }
        if(!"upload".equals(reservation.ownerKind))return StorageAdmission.OwnerState.uncertain();
        try(var tx=db.beginTx()){
            var intent=reservation.writeIntent();
            if (!(intent.get("stagingPath") instanceof String staging) || !(intent.get("resourceId") instanceof String resourceId))
                return StorageAdmission.OwnerState.uncertain();
            CoreRepository core=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));
            Node transfer=core.find("DepositTransfer","id",reservation.ownerId);
            Node location=core.find("DepositResourceLocation","id",resourceId);
            if (transfer==null && location==null && !Files.exists(files.path(staging),java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && !content.exists(resourceId))return StorageAdmission.OwnerState.absent();
            if (transfer==null || location==null || !resourceId.equals(transfer.getProperty("resourceId",null))
                    || !reservation.ownerId.equals(location.getProperty("uploadId",null))
                    || !Objects.equals(transfer.getProperty("namespaceId",null),location.getProperty("namespaceId",null))
                    || Boolean.TRUE.equals(location.getProperty("quarantined",false)))
                return StorageAdmission.OwnerState.uncertain();
            String state=(String)transfer.getProperty("state","");
            if ("finished".equals(state) && Boolean.TRUE.equals(location.getProperty("published",false))
                    || "expired".equals(state) && Boolean.TRUE.equals(location.getProperty("collected",false)))
                return StorageAdmission.OwnerState.absent();
            return StorageAdmission.OwnerState.uncertain();
        }
    }

    private StorageAdmission.OwnerState reconcileCommittedImportWritesAfterRecovery(StorageAdmission.Reservation reservation)throws java.io.IOException {
        if(!Set.of("import-apply","import-write").contains(reservation.ownerKind))return StorageAdmission.OwnerState.uncertain();
        if(!historyScratchAbsent(reservation))return StorageAdmission.OwnerState.uncertain();
        Object scope=reservation.writeIntent().get("receiptScope");
        if(!(scope instanceof String receiptScope)||reservation.operationId==null)return StorageAdmission.OwnerState.uncertain();
        Object actorId=reservation.writeIntent().get("actorId");
        if(!(actorId instanceof String actor)||!reservationOwnerMatches(reservation,receiptScope,actor))return StorageAdmission.OwnerState.uncertain();
        Object resultNamespaceRaw=reservation.writeIntent().getOrDefault("resultNamespaceId",receiptScope);
        if(!(resultNamespaceRaw instanceof String resultNamespace))return StorageAdmission.OwnerState.uncertain();
        try(var tx=db.beginTx()){
            CoreRepository core=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));
            Node receipt=core.receipt(actor,receiptScope,reservation.operationId);
            if(receipt==null)return core.receipt(receiptScope,reservation.operationId)==null
                ?StorageAdmission.OwnerState.absent():StorageAdmission.OwnerState.uncertain();
            if(!receiptScope.equals(receipt.getProperty("scope",null))
                ||!reservation.operationId.equals(receipt.getProperty("operationId",null)))
                return StorageAdmission.OwnerState.uncertain();
            if(!actor.equals(receipt.getProperty("actorId",null)))
                return StorageAdmission.OwnerState.uncertain();
            Object resultId=receipt.getProperty("importResultId",null);
            if(!(resultId instanceof String id))return StorageAdmission.OwnerState.uncertain();
            if(!ImportResultContract.verifiesResult(core,receipt,resultNamespace))return StorageAdmission.OwnerState.uncertain();
            return StorageAdmission.OwnerState.absent();
        } catch(RuntimeException invalidReceipt) {
            return StorageAdmission.OwnerState.uncertain();
        }
    }

    private static boolean reservationOwnerMatches(StorageAdmission.Reservation reservation,String scope,String actor) {
        Object destination=reservation.writeIntent().getOrDefault("resultNamespaceId",scope);
        return destination instanceof String namespace
            &&ImportWriteBudget.reservationOwnerId(scope,namespace,actor,reservation.operationId).equals(reservation.ownerId);
    }

    StorageAdmission.OwnerState reconcileRuntimeImportWrite(StorageAdmission.Reservation reservation)throws java.io.IOException {
        if(!Set.of("import-apply","import-write").contains(reservation.ownerKind))return StorageAdmission.OwnerState.uncertain();
        if(Boolean.TRUE.equals(reservation.writeIntent().get("scratchCleanupPending")))return StorageAdmission.OwnerState.uncertain();
        if(!historyScratchAbsent(reservation))return StorageAdmission.OwnerState.uncertain();
        Object scope=reservation.writeIntent().get("receiptScope"),actor=reservation.writeIntent().get("actorId");
        if(!(scope instanceof String receiptScope)||!(actor instanceof String actorId)||reservation.operationId==null
            ||!reservationOwnerMatches(reservation,receiptScope,actorId))return StorageAdmission.OwnerState.uncertain();
        Object resultNamespaceRaw=reservation.writeIntent().getOrDefault("resultNamespaceId",receiptScope);
        if(!(resultNamespaceRaw instanceof String resultNamespace))return StorageAdmission.OwnerState.uncertain();
        try(var tx=db.beginTx()){
            CoreRepository core=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));Node receipt=core.receipt(actorId,receiptScope,reservation.operationId);
            if(receipt==null)return StorageAdmission.OwnerState.uncertain();
            if(!receiptScope.equals(receipt.getProperty("scope",null))||!reservation.operationId.equals(receipt.getProperty("operationId",null))||!actorId.equals(receipt.getProperty("actorId",null)))return StorageAdmission.OwnerState.uncertain();
            Object resultId=receipt.getProperty("importResultId",null);if(!(resultId instanceof String id))return StorageAdmission.OwnerState.uncertain();
            if(!ImportResultContract.verifiesResult(core,receipt,resultNamespace))return StorageAdmission.OwnerState.uncertain();
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
            operations.tick();
            jobs.expireImportPlans(); jobs.expireImportResults(); jobs.expireTables();
            jobs.expireExchange();
            for (String id : ids("DepositResourceLocation")) {
                if (Thread.currentThread().isInterrupted()) return;
                jobs.process(id, false);
            }
            for (String id : ids("DepositPruneItem")) jobs.collect(id);
            // Runtime cleanup is safe only when a durable receipt proves the import transaction committed.
            admission.reconcile(reservation->Set.of("ocfl-publication","tabular-load","import-plan","export-scratch","exchange-stage").contains(reservation.ownerKind)
                ?reconcileOutputIntent(reservation):reconcileRuntimeImportWrite(reservation));
            operations.finishCleanup();
        } catch (Exception e) { diagnostic = "Resource maintenance pending: " + e.getClass().getSimpleName(); }
    }
}
