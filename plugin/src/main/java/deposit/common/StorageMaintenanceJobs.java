package deposit.common;

import static deposit.repository.ResourceRepository.*;
import deposit.repository.CoreRepository;
import deposit.repository.ResourceRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
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
                if (number(table, "expiresAt", 0) <= now)
                    expired.add(CoreRepository.id(table));
            }
        }
        for (String id : expired) try (var tx = db.beginTx()) {
            CoreRepository core = core(tx);
            Node table = core.find("DepositTabularTable", "id", id);
            if (table == null || number(table, "expiresAt", 0) > now) continue;
            core.lockNamespace(text(table, "namespaceId"));
            table = core.find("DepositTabularTable", "id", id);
            if (table == null || number(table, "expiresAt", 0) > now) continue;
            Files.deleteIfExists(files.path(text(table, "path")));
            if (table.hasProperty("rowIndexPath")) Files.deleteIfExists(files.path(text(table, "rowIndexPath")));
            String source = text(table, "sourceResourceId");
            for (Node plan : core.nodes("DepositImportPlan", Map.of("tableId", id))) plan.removeProperty("tableId");
            for (Node lease : core.nodes("DepositResourceLease", Map.of("tableId", id))) lease.delete();
            table.delete();
            Node loc = core.find("DepositResourceLocation", "id", source);
            if (loc != null) new ResourceRepository(tx, core).orphan(loc);
            tx.commit();
        }
    }

    void expireExchange() throws IOException {
        long now=server.sessionTime();
        var candidates=new LinkedHashSet<String>();
        try(var tx=db.beginTx()){
            try(var rows=tx.execute("""
                    MATCH (stage:DepositExchangeStage)
                    WHERE (stage.cleanupAfter <= $now AND coalesce(stage.cleaned,false)=false)
                        OR stage.inputCleanup=true OR stage.removeAfter <= $now
                    RETURN stage.id AS id LIMIT 64
                    """,Map.of("now",now))){
                while(rows.hasNext())candidates.add((String)rows.next().get("id"));
            }
            var core=core(tx);
            try(var writers=core.scanNodes("DepositExchangeStage",Map.of("state","uploading"))){
                while(writers.hasNext()&&candidates.size()<128){
                    Node writer=writers.next();
                    if(UploadBinding.abandoned(server,core,writer))candidates.add(CoreRepository.id(writer));
                }
            }
        }
        for(String id:candidates)cleanExchange(id,now);
    }

    private void cleanExchange(String id,long now)throws IOException {
        String closed=null;
        try(var tx=db.beginTx()){
            var core=core(tx);
            Node stage=core.find("DepositExchangeStage","id",id);
            if(stage==null)return;
            tx.acquireWriteLock(stage);
            stage=core.find("DepositExchangeStage","id",id);
            if(stage==null)return;
            if("uploading".equals(stage.getProperty("state"))&&UploadBinding.abandoned(server,core,stage)){
                stage.setProperty("state","aborted");
                stage.setProperty("cleanupAfter",now);
                stage.setProperty("removeAfter",now+ResourceFiles.LEASE);
            }
            boolean removing=(long)stage.getProperty("cleanupAfter",Long.MAX_VALUE)<=now;
            boolean input=Boolean.TRUE.equals(stage.getProperty("inputCleanup",false));
            if((removing&&!Boolean.TRUE.equals(stage.getProperty("cleaned",false)))||input){
                String path="staging/exchange/"+RequestContext.uuid(id);
                if(removing)files.deleteOwnedStagingTree(path);
                else files.deleteOwnedStagingTree(path+"/input");
                long deleted;
                try(var rows=tx.execute("""
                        MATCH (member:DepositExchangeUploadMember {uploadId:$id})
                        WITH member LIMIT 256 DELETE member RETURN count(*) AS deleted
                        """,Map.of("id",id))){
                    deleted=(long)rows.next().get("deleted");
                }
                if(deleted<256){
                    stage.removeProperty("inputCleanup");
                    if(removing){
                        UploadBinding.cleaned(core,id);
                        try(var attempts=core.scanNodes("DepositOperationAttempt",Map.of("uploadId",id))){
                            if(!attempts.hasNext())stage.setProperty("cleaned",true);
                        }
                    }
                }
            }
            if(Boolean.TRUE.equals(stage.getProperty("cleaned",false))&&(long)stage.getProperty("removeAfter",Long.MAX_VALUE)<=now){
                closed=(String)stage.getProperty("admissionId");
                stage.delete();
            }
            tx.commit();
        }
        if(closed!=null)server.storage.admission.closeIfPresent(closed);
    }

    void expireImportResults() {
        // Stateless leases have a fixed maximum deadline. Collect only after that deadline,
        // holding the result lock against leased readers and already-admitted apply calls.
        long cutoff=server.sessionTime()-ImportResultContract.READER;
        try(var tx=db.beginTx()) {
            String id;
            try(var candidates=tx.execute("""
                    MATCH (result:DepositImportResult) WHERE result.expiresAt <= $cutoff
                    RETURN result.id AS id ORDER BY result.expiresAt LIMIT 1
                    """,Map.of("cutoff",cutoff))) {
                if(!candidates.hasNext())return;
                id=(String)candidates.next().get("id");
            }
            Node result=core(tx).find(ImportResultContract.LABEL,"id",id);
            if(result==null)return;
            tx.acquireWriteLock(result);
            result=core(tx).find(ImportResultContract.LABEL,"id",id);
            if(result==null)return;
            if(number(result,"expiresAt",Long.MAX_VALUE)>server.sessionTime()-ImportResultContract.READER)return;
            if(deleteResultSegments(tx,"DepositImportResultEntry",id)<256
                    && deleteResultSegments(tx,"DepositImportResultCollection",id)<256) {
                CoreRepository core=core(tx);
                String namespace=text(result,"namespaceId");
                String scope="server".equals(text(result,"scope"))?"server":namespace;
                Node receipt=core.receipt(text(result,"actorId"),scope,text(result,"operationId"));
                if(receipt!=null && id.equals(receipt.getProperty("importResultId",null))
                        && ImportResultContract.verifiesResult(core,receipt,namespace)) {
                    receipt.setProperty("importResultCollected",true);
                }
                result.delete();
            }
            tx.commit();
        }
    }

    private long deleteResultSegments(Transaction tx,String label,String resultId) {
        try(var rows=tx.execute("MATCH (segment:"+label+" {resultId:$id}) "
                +"WITH segment LIMIT 256 DELETE segment RETURN count(*) AS deleted",Map.of("id",resultId))) {
            return (long)rows.next().get("deleted");
        }
    }

    void expireImportPlans() throws IOException {
        long now = server.sessionTime();
        List<String> settled = new ArrayList<>();
        try (var tx = db.beginTx()) {
            for (Node plan : core(tx).nodes("DepositImportPlan", Map.of()))
                if (number(plan, "expiresAt", 0) <= now || abandonedPlan(plan))
                    settled.add(CoreRepository.id(plan));
        }
        for (String id : settled) try (var tx = db.beginTx()) {
            CoreRepository core = core(tx);
            Node plan = core.find("DepositImportPlan", "id", id);
            if (plan == null) continue;
            core.lockNamespace(text(plan, "namespaceId"));
            plan = core.find("DepositImportPlan", "id", id);
            if (plan == null) continue;
            boolean expired = number(plan, "expiresAt", 0) <= now;
            if (!expired && !abandonedPlan(plan)) continue;
            tx.acquireWriteLock(plan);
            String path = plan.hasProperty("stagingPath") ? text(plan, "stagingPath") : null;
            String admissionId = (String)plan.getProperty("admissionId",null);
            if (path != null) Files.deleteIfExists(files.path(path));
            if (expired) plan.delete();
            else {
                plan.setProperty("state","cancelled");
                plan.removeProperty("stagingPath");
                plan.removeProperty("admissionId");
            }
            tx.commit();
            if(admissionId!=null)try{server.storage.admission.closeIfPresent(admissionId);}catch(IOException failure){diagnostic.accept("Import plan storage admission cleanup pending");}
        }
    }

    private boolean abandonedPlan(Node plan) {
        return "ready".equals(plan.getProperty("state",null)) && UploadBinding.abandoned(server,plan);
    }

    void process(String id, boolean recovery) {
        try (var tx = db.beginTx()) {
            CoreRepository core = core(tx);
            ResourceRepository resources = new ResourceRepository(tx, core);
            Node loc = resources.location(id);
            if (loc == null) return;
            core.lockNamespace(text(loc, "namespaceId"));
            resources.lock(id);
            if (Boolean.TRUE.equals(loc.getProperty("collected",false)) || Boolean.TRUE.equals(loc.getProperty("quarantined",false))
                    || loc.hasProperty("deleting")) return;
            Node upload = loc.hasProperty("uploadId") ? resources.transfer(text(loc, "uploadId")) : null;
            if (upload != null && upload.hasProperty("writerIncarnation")
                    && ("aborted".equals(upload.getProperty("state")) || "uploading".equals(upload.getProperty("state")) && UploadBinding.abandoned(server,core,upload))) {
                cleanAbandonedResourceUpload(core,resources,loc,upload);
                tx.commit();
                return;
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

    private void cleanAbandonedResourceUpload(CoreRepository core, ResourceRepository resources, Node loc, Node upload) throws IOException {
        String id=CoreRepository.id(loc), uploadId=CoreRepository.id(upload), namespace=text(loc,"namespaceId");
        upload.setProperty("state","expired");
        upload.setProperty("writerFenced",true);
        upload.setProperty("expires",0L);
        loc.setProperty("leaseUntil",0L);
        if(!id.equals(upload.getProperty("resourceId")) || !namespace.equals(upload.getProperty("namespaceId"))
                || resources.resource(namespace,id)!=null || resources.protectedContent(loc)) {
            loc.setProperty("quarantined",true);
            String message="Abandoned upload ownership or reachability requires verification";
            loc.setProperty("diagnostic",message);
            diagnostic.accept(message);
            UploadBinding.cleanupFailed(core,uploadId);
            return;
        }
        if (loc.hasProperty("stagingPath")) {
            String expected="staging/"+namespace+"/"+uploadId+".part";
            if (!expected.equals(text(loc,"stagingPath"))) throw new IOException("Abandoned upload staging ownership is unclear");
            Files.deleteIfExists(files.path(expected));
            loc.removeProperty("stagingPath");
        }
        if (content.get().exists(id)) {
            content.get().verify(resources.content(loc));
            content.get().purge(id);
        }
        if (upload.hasProperty("admissionId")) server.storage.admission.closeIfPresent(text(upload,"admissionId"));
        loc.setProperty("collected",true);
        loc.removeProperty("diagnostic");
        UploadBinding.cleaned(core,uploadId);
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
                if (job.hasProperty("incarnation") && !server.runId.equals(job.getProperty("incarnation"))) {
                    item.setProperty("state","skipped");tx.commit();resolved[0]=true;return;
                }
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
                    resources.record("DepositResourceLocation", found.resourceId(), Json.map("namespaceId", unknown, "storedFilename", found.storedFilename(), "bytes", found.bytes(), "checksum", found.checksum(), "published", true, "quarantined",true,"orphanSince", server.sessionTime(), "leaseUntil", 0L));
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
                        resources.record("DepositResourceLocation", UUID.randomUUID().toString(), Json.map("namespaceId", unknown, "stagingPath", relative, "bytes", Files.size(path), "checksum", ResourceFiles.checksum(path), "quarantined",true,"orphanSince", server.sessionTime(), "leaseUntil", 0L));
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
