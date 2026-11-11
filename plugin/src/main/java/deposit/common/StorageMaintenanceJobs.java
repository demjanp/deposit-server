package deposit.common;

import static deposit.repository.ResourceRepository.*;
import deposit.repository.CoreRepository;
import deposit.repository.ResourceRepository;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/** Standalone storage maintenance transactions and filesystem reconciliation jobs. */
final class StorageMaintenanceJobs {
    private static final int TRANSFER_CHUNK_CLEANUP_BATCH = 256;
    private static final String DELETE_TERMINAL_TRANSFER_CHUNKS = "MATCH (transfer:DepositTransfer) WHERE transfer.state IN ['finished','aborted','expired'] MATCH (chunk:DepositTransferChunk {uploadId: transfer.id}) WITH chunk LIMIT $batchSize DELETE chunk RETURN count(*) AS deleted";
    private final ServerContext server;
    private final GraphDatabaseAPI db;
    private final ResourceFiles files;
    private final Supplier<ResourceStorage> content;
    private final Consumer<String> diagnostic;

    StorageMaintenanceJobs(ServerContext server, GraphDatabaseAPI db, ResourceFiles files,
                           Supplier<ResourceStorage> content, Consumer<String> diagnostic) {
        this.server = server;
        this.db = db;
        this.files = files;
        this.content = content;
        this.diagnostic = diagnostic;
    }

    private CoreRepository core(Transaction tx) {
        return new CoreRepository(tx, java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC));
    }

    void expireTables() throws IOException {
        long now = server.sessionTime();
        List<String> expired = new ArrayList<>();
        try (var tx = db.beginTx()) {
            CoreRepository core = core(tx);
            for (Node table : core.nodes("DepositTabularTable", Map.of())) {
                if (number(table, "expiresAt", 0) <= now && !protectedByReadyPlan(core, CoreRepository.id(table), now))
                    expired.add(CoreRepository.id(table));
            }
        }
        for (String id : expired) try (var tx = db.beginTx()) {
            CoreRepository core = core(tx);
            Node table = core.find("DepositTabularTable", "id", id);
            if (table == null || number(table, "expiresAt", 0) > now) continue;
            core.lockNamespace(text(table, "namespaceId"));
            if (protectedByReadyPlan(core, id, now)) continue;
            Files.deleteIfExists(files.path(text(table, "path")));
            if (table.hasProperty("rowIndexPath")) Files.deleteIfExists(files.path(text(table, "rowIndexPath")));
            String source = text(table, "sourceResourceId");
            for (Node lease : core.nodes("DepositResourceLease", Map.of("tableId", id))) lease.delete();
            table.delete();
            Node loc = core.find("DepositResourceLocation", "id", source);
            if (loc != null) new ResourceRepository(tx, core).orphan(loc);
            tx.commit();
        }
    }

    private boolean protectedByReadyPlan(CoreRepository core, String tableId, long now) {
        for (Node plan : core.nodes("DepositImportPlan", Map.of("tableId", tableId)))
            if ("ready".equals(text(plan, "state")) && number(plan, "expiresAt", 0) > now) return true;
        return false;
    }

    void expireImportPlans() throws IOException {
        long now = server.sessionTime();
        List<String> expired = new ArrayList<>();
        try (var tx = db.beginTx()) {
            for (Node plan : core(tx).nodes("DepositImportPlan", Map.of()))
                if (number(plan, "expiresAt", 0) <= now) expired.add(CoreRepository.id(plan));
        }
        for (String id : expired) try (var tx = db.beginTx()) {
            CoreRepository core = core(tx);
            Node plan = core.find("DepositImportPlan", "id", id);
            if (plan == null || number(plan, "expiresAt", 0) > now) continue;
            String path = plan.hasProperty("stagingPath") ? text(plan, "stagingPath") : null;
            String admissionId=plan.hasProperty("admissionId")?text(plan,"admissionId"):null;
            if (path != null) Files.deleteIfExists(files.path(path));
            plan.delete();
            tx.commit();
            if(admissionId!=null)try{server.storage.admission.close(server.storage.admission.resume(admissionId));}catch(IOException failure){diagnostic.accept("Expired import storage admission cleanup pending");}
        }
        try(var tx=db.beginTx()){
            collectTerminalTransferChunks(tx,TRANSFER_CHUNK_CLEANUP_BATCH);
            tx.commit();
        }catch(RuntimeException failure){diagnostic.accept("Upload chunk metadata cleanup pending: "+failure.getClass().getSimpleName());}
    }

    static long collectTerminalTransferChunks(Transaction tx,int batchSize){
        if(batchSize<1)throw new IllegalArgumentException("Chunk cleanup batch must be positive");
        try(var result=tx.execute(DELETE_TERMINAL_TRANSFER_CHUNKS,Map.of("batchSize",batchSize))){
            return ((Number)result.next().get("deleted")).longValue();
        }
    }

    void process(String id, boolean recovery) {
        try (var tx = db.beginTx()) {
            CoreRepository core = core(tx);
            ResourceRepository resources = new ResourceRepository(tx, core);
            Node loc = resources.location(id);
            if (loc == null) return;
            core.lockNamespace(text(loc, "namespaceId"));
            resources.lock(id);
            if (Boolean.TRUE.equals(loc.getProperty("collected", false)) || loc.hasProperty("deleting")) return;
            Node upload = loc.hasProperty("uploadId") ? resources.transfer(text(loc, "uploadId")) : null;
            if (recovery && upload != null && "uploading".equals(upload.getProperty("state"))) {
                Path staged = files.path(text(loc, "stagingPath"));
                long offset = number(upload, "nextOffset", 0);
                if (Files.exists(staged)) try (var channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                    if (channel.size() < offset) throw new IOException("Committed upload content missing");
                    channel.truncate(offset);
                    channel.force(true);
                } else if (offset > 0) throw new IOException("Committed upload content missing");
                if (content.get().exists(id)) content.get().verify(resources.content(loc));
            }
            resources.expire(loc);
            if (Boolean.TRUE.equals(loc.getProperty("published", false))) {
                if (recovery) content.get().verify(resources.content(loc));
                if (loc.hasProperty("stagingPath")) {
                    content.get().verify(resources.content(loc));
                    server.storagePoint("staging-cleanup-before");
                    Files.deleteIfExists(files.path(text(loc, "stagingPath")));
                    server.storagePoint("staging-cleanup-after");
                    loc.removeProperty("stagingPath");
                }
            }
            loc.removeProperty("diagnostic");
            tx.commit();
        } catch (Exception e) {
            recordFailure(id, "Resource recovery pending: " + e.getClass().getSimpleName());
            if (recovery) throw new IllegalStateException("Cannot reconcile resource " + id, e);
        }
    }

    private void recordFailure(String id, String message) {
        diagnostic.accept(message);
        try (var tx = db.beginTx()) {
            CoreRepository core = core(tx);
            Node loc = core.find("DepositResourceLocation", "id", id);
            if (loc != null) {
                core.lockNamespace(text(loc, "namespaceId"));
                loc.setProperty("diagnostic", message);
            }
            tx.commit();
        } catch (Exception ignored) { }
    }

    void collect(String itemId) {
        StorageAdmission.Reservation reservation;
        try{reservation=server.storage.admission.acquire("maintenance-prune",itemId,null,"prune-maintenance",Map.of(files.root,64L*1024),Json.map("phase","prepare","itemId",itemId));}
        catch(IOException|StorageAdmission.StoragePressureException pressure){diagnostic.accept("Collection admission pending: "+pressure.getClass().getSimpleName());return;}
        boolean[] resolved={false};
        try (var tx = db.beginTx()) {
            CoreRepository core = core(tx);
            ResourceRepository resources = new ResourceRepository(tx, core);
            Node item = core.find("DepositPruneItem", "id", itemId);
            if (item == null || !Set.of("pending", "deleting", "failed").contains(text(item, "state"))) {resolved[0]=true;return;}
            String resource = text(item, "resourceId");
            Node loc = resources.location(resource);
            Node job = core.find("DepositPruneJob", "id", item.getProperty("jobId"));
            if (loc == null || Boolean.TRUE.equals(loc.getProperty("collected", false))) {
                item.setProperty("state", "completed"); tx.commit();resolved[0]=true;return;
            }
            core.lockNamespace(text(loc, "namespaceId"));
            resources.lock(resource);
            long age = number(job, "minimumAge", 0);
            if ("failed".equals(item.getProperty("state")))
                item.setProperty("state", itemId.equals(loc.getProperty("deleting", null)) ? "deleting" : "pending");
            if (text(item, "state").equals("pending")) {
                if (!resources.eligible(loc, age)) { item.setProperty("state", "skipped"); tx.commit();resolved[0]=true;return; }
                loc.setProperty("deleting", itemId);
                item.setProperty("state", "deleting");
                item.setProperty("deletionBytes", physicalBytes(loc));
                tx.commit();resolved[0]=true;return;
            }
            if (!itemId.equals(loc.getProperty("deleting", null))) throw new IllegalStateException("Collection intent mismatch");
            if (resources.protectedContent(loc) || resources.now() - number(loc, "orphanSince", resources.now()) < age) {
                loc.removeProperty("deleting"); item.setProperty("state", "skipped"); tx.commit();resolved[0]=true;return;
            }
            server.storagePoint("collection-before");
            content.get().purge(resource);
            if (loc.hasProperty("stagingPath")) Files.deleteIfExists(files.path(text(loc, "stagingPath")));
            server.storagePoint("collection-after");
            if (content.get().exists(resource)) throw new IOException("Resource purge incomplete");
            loc.setProperty("collected", true); loc.removeProperty("deleting"); loc.removeProperty("stagingPath"); loc.setProperty("leaseUntil", 0L);
            Node publicResource = resources.resource(text(loc, "namespaceId"), resource);
            if (publicResource != null) publicResource.setProperty("state", "collected");
            item.setProperty("reclaimedBytes", number(item, "deletionBytes", 0)); item.setProperty("state", "completed"); item.removeProperty("diagnostic"); tx.commit();resolved[0]=true;
        } catch (Exception e) {
            String message = "Collection failed: " + e.getClass().getSimpleName();
            diagnostic.accept(message);
            try (var tx = db.beginTx()) {
                Node item = core(tx).find("DepositPruneItem", "id", itemId);
                if (item != null) { item.setProperty("state", "failed"); item.setProperty("diagnostic", message); }
                tx.commit();
            } catch (Exception ignored) { }
        } finally {if(resolved[0])try{server.storage.admission.close(reservation);}catch(IOException failure){diagnostic.accept("Collection admission cleanup pending");}}
    }

    void discoverOrphans() {
        Set<String> knownIds = new HashSet<>(), knownStaging = new HashSet<>();
        try (var tx = db.beginTx()) {
            for (Node loc : core(tx).nodes("DepositResourceLocation", Map.of())) {
                knownIds.add(CoreRepository.id(loc));
                if (loc.hasProperty("stagingPath")) knownStaging.add(text(loc, "stagingPath").replace('\\', '/'));
            }
            for (Node plan : core(tx).nodes("DepositImportPlan", Map.of()))
                if (number(plan, "expiresAt", 0) > server.sessionTime() && plan.hasProperty("stagingPath")) knownStaging.add(text(plan, "stagingPath").replace('\\', '/'));
            for (Node table : core(tx).nodes("DepositTabularTable", Map.of())) if (number(table, "expiresAt", 0) > server.sessionTime()) {
                if (table.hasProperty("path")) knownStaging.add(text(table, "path").replace('\\', '/'));
                if (table.hasProperty("rowIndexPath")) knownStaging.add(text(table, "rowIndexPath").replace('\\', '/'));
            }
        }
        String unknown = "00000000-0000-0000-0000-000000000000";
        try {
            for (var found : content.get().discover()) if (!knownIds.contains(found.resourceId())) {
                try (var tx = db.beginTx()) {
                    CoreRepository core = core(tx); ResourceRepository resources = new ResourceRepository(tx, core);
                    if (core.find("DepositNamespaceLock", "id", unknown) == null) resources.record("DepositNamespaceLock", unknown, Map.of());
                    core.lockNamespace(unknown);
                    resources.record("DepositResourceLocation", found.resourceId(), Json.map("namespaceId", unknown, "storedFilename", found.storedFilename(), "bytes", found.bytes(), "checksum", found.checksum(), "published", true, "orphanSince", server.sessionTime(), "leaseUntil", 0L));
                    tx.commit();
                }
            }
            try (var paths = Files.walk(files.root.resolve("staging"))) {
                for (Path path : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    String relative = files.root.relativize(path).toString().replace('\\', '/'); files.path(relative);
                    if (knownStaging.contains(relative)) continue;
                    try (var tx = db.beginTx()) {
                        CoreRepository core = core(tx); ResourceRepository resources = new ResourceRepository(tx, core);
                        if (core.find("DepositNamespaceLock", "id", unknown) == null) resources.record("DepositNamespaceLock", unknown, Map.of());
                        core.lockNamespace(unknown);
                        resources.record("DepositResourceLocation", UUID.randomUUID().toString(), Json.map("namespaceId", unknown, "stagingPath", relative, "bytes", Files.size(path), "checksum", ResourceFiles.checksum(path), "orphanSince", server.sessionTime(), "leaseUntil", 0L));
                        tx.commit();
                    }
                }
            }
        } catch (Exception e) { throw new IllegalStateException("Cannot reconcile resource orphans", e); }
    }

    private long physicalBytes(Node loc) throws IOException {
        long bytes = content.get().physicalBytes(CoreRepository.id(loc));
        if (loc.hasProperty("stagingPath")) { Path path = files.path(text(loc, "stagingPath")); if (Files.exists(path)) try { bytes = Math.addExact(bytes, Files.size(path)); } catch (ArithmeticException overflow) { throw new IOException("Resource physical size is not representable", overflow); } }
        return bytes;
    }
}
