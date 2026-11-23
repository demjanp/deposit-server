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
    private static final int TRANSFER_CHUNK_CLEANUP_BATCH = 256;
    private static final int CLONE_UPLOAD_CLEANUP_BATCH = 256;
    private static final int CLONE_UPLOAD_CANDIDATE_BATCH = 64;
    private static final Set<String> CLONE_UPLOAD_PROCEDURES = Set.of("deposit.import.cloneNamespace","deposit.import.beginCloneUpload",
            "deposit.import.cloneUploadChunk","deposit.import.finishCloneUpload","deposit.import.abortCloneUpload");
    // PreparedActionRepository always supplies a recovery key. Its presence lets Cypher use the composite constraint.
    static final String CLONE_ACTIONS_FOR_ACTOR = """
            MATCH (n:DepositPreparedAction {actorId: $actor})
            WHERE n.recoveryKey IS NOT NULL
            RETURN n
            """;
    private static final String DELETE_TERMINAL_TRANSFER_CHUNKS = "MATCH (transfer:DepositTransfer) WHERE transfer.state IN ['finished','aborted','expired'] MATCH (chunk:DepositTransferChunk {uploadId: transfer.id}) WITH chunk LIMIT $batchSize DELETE chunk RETURN count(*) AS deleted";
    private static final String DELETE_CLONE_UPLOAD_CHUNKS = "MATCH (chunk:DepositCloneUploadChunk {uploadId:$uploadId}) WITH chunk ORDER BY chunk.offset LIMIT $batchSize DELETE chunk RETURN count(*) AS deleted";
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

    void expireCloneUploads() throws IOException {
        long now=server.sessionTime();List<String> candidates=new ArrayList<>(CLONE_UPLOAD_CANDIDATE_BATCH);
        String select="MATCH (upload:DepositCloneUpload) WHERE upload.expires <= $now RETURN upload.id AS id ORDER BY coalesce(upload.cleanupAttempts,0), upload.id LIMIT $limit";
        try(var tx=db.beginTx();var rows=tx.execute(select,Map.of("now",now,"limit",CLONE_UPLOAD_CANDIDATE_BATCH))){while(rows.hasNext())candidates.add((String)rows.next().get("id"));}
        for(String id:candidates)cleanupCloneUpload(id);
        candidates.clear();
        try (var tx=db.beginTx();var uploading=core(tx).scanNodes("DepositCloneUpload", Map.of("state","uploading"))) {
            CoreRepository core=core(tx);
            while (uploading.hasNext() && candidates.size()<CLONE_UPLOAD_CANDIDATE_BATCH) {
                Node upload=uploading.next();
                if (UploadBinding.abandoned(server,core,upload)) candidates.add(CoreRepository.id(upload));
            }
        }
        for(String id:candidates)cleanupCloneUpload(id);
    }

    private void cleanupCloneUpload(String id)throws IOException {
        String admissionId=null;boolean pathRemoved=false,chunksRemoved=false;
        try(var tx=db.beginTx()){
            CoreRepository core=core(tx);Node upload=core.find("DepositCloneUpload","id",id);if(upload==null)return;
            server.storagePoint("clone-upload-expiry-before-lock");tx.acquireWriteLock(upload);upload=core.find("DepositCloneUpload","id",id);server.storagePoint("clone-upload-expiry-after-lock");if(upload==null)return;
            String state=text(upload,"state");long now=server.sessionTime();
            if(Set.of("uploading","finished").contains(state)){
                if ("uploading".equals(state) && upload.hasProperty("writerIncarnation")) {
                    if (!UploadBinding.abandoned(server,core,upload)) return;
                } else if(number(upload,"expires",0)>now || !upload.hasProperty("writerIncarnation") && hasUnresolvedCloneAction(tx,core,text(upload,"actorId")))return;
                upload.setProperty("state","expired");upload.setProperty("expires",0L);state="expired";
                if(upload.hasProperty("writerIncarnation")) upload.setProperty("writerFenced",true);
            }
            if(!Set.of("consumed","aborted","expired").contains(state)||number(upload,"expires",0)>server.sessionTime())return;
            if("consumed".equals(state)&&!hasCommittedCloneReceipt(core,upload))return;
            Files.deleteIfExists(files.path(text(upload,"stagingPath")));
            upload.setProperty("cleanupPathRemoved",true);pathRemoved=true;
            try(var result=tx.execute(DELETE_CLONE_UPLOAD_CHUNKS,Map.of("uploadId",id,"batchSize",CLONE_UPLOAD_CLEANUP_BATCH))){result.next();}
            chunksRemoved=!hasCloneChunks(tx,id);
            if(chunksRemoved)upload.setProperty("cleanupChunksRemoved",true);
            upload.setProperty("cleanupAttempts",number(upload,"cleanupAttempts",0)+1);
            admissionId=upload.hasProperty("admissionId")?text(upload,"admissionId"):null;
            tx.commit();
        }
        if(!pathRemoved||!chunksRemoved)return;
        if(admissionId!=null)try{server.storage.admission.closeIfPresent(admissionId);}catch(IOException failure){diagnostic.accept("Terminal clone upload admission cleanup pending");return;}
        try(var tx=db.beginTx()){
            CoreRepository core=core(tx);Node upload=core.find("DepositCloneUpload","id",id);if(upload==null)return;
            tx.acquireWriteLock(upload);upload=core.find("DepositCloneUpload","id",id);if(upload==null)return;
            if(!Set.of("consumed","aborted","expired").contains(text(upload,"state"))
                    ||!Boolean.TRUE.equals(upload.getProperty("cleanupPathRemoved",false))
                    ||!Boolean.TRUE.equals(upload.getProperty("cleanupChunksRemoved",false))
                    ||Files.exists(files.path(text(upload,"stagingPath")))||hasCloneChunks(tx,id))return;
            if(admissionId!=null&&Files.exists(files.root.resolve("admission").resolve(admissionId+".json")))return;
            UploadBinding.cleaned(core,id);upload.delete();tx.commit();
        }
    }

    private boolean hasUnresolvedCloneAction(Transaction tx, CoreRepository core, String actor) {
        try (var rows = tx.execute(CLONE_ACTIONS_FOR_ACTOR, Map.of("actor", actor));
             var actions = rows.<Node>columnAs("n")) {
            return actions.stream().anyMatch(action -> CLONE_UPLOAD_PROCEDURES.contains(action.getProperty("procedure", ""))
                    && core.receipt("server", (String) action.getProperty("operationId")) == null);
        }
    }

    private boolean hasCommittedCloneReceipt(CoreRepository core,Node upload){
        Object operation=upload.getProperty("consumedOperationId",null),actor=upload.getProperty("consumedActorId",null);
        Object expectedResult=upload.getProperty("consumedResultId",null),expectedNamespace=upload.getProperty("consumedNamespaceId",null);
        if(!(operation instanceof String operationId)||!(actor instanceof String actorId)
                ||!(expectedResult instanceof String resultId)||!(expectedNamespace instanceof String namespaceId)
                ||!actorId.equals(upload.getProperty("actorId",null)))return false;
        Node receipt=core.receipt(actorId,"server",operationId);if(receipt==null
                ||!operationId.equals(receipt.getProperty("operationId",null))
                ||!actorId.equals(receipt.getProperty("actorId",null)))return false;
        Object storedResult=receipt.getProperty("importResultId",null);if(!resultId.equals(storedResult))return false;
        return ImportResultContract.verifiesResult(core,receipt,namespaceId);
    }

    private boolean hasCloneChunks(Transaction tx,String uploadId){
        try(var rows=tx.execute("MATCH (chunk:DepositCloneUploadChunk {uploadId:$uploadId}) RETURN chunk.id AS id LIMIT 1",Map.of("uploadId",uploadId))){return rows.hasNext();}
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
                    if(!receipt.hasProperty("resultKind")) {
                        String kind=text(result,"kind");
                        receipt.setProperty("resultKind","diagnostic".equals(kind)?"analysis":kind);
                    }
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
        List<String> expired = new ArrayList<>();
        try (var tx = db.beginTx()) {
            for (Node plan : core(tx).nodes("DepositImportPlan", Map.of()))
                if (number(plan, "expiresAt", 0) <= now) expired.add(CoreRepository.id(plan));
        }
        for (String id : expired) try (var tx = db.beginTx()) {
            CoreRepository core = core(tx);
            Node plan = core.find("DepositImportPlan", "id", id);
            if (plan == null || number(plan, "expiresAt", 0) > now) continue;
            // Inspection can renew the plan while cleanup waits for its Namespace.
            core.lockNamespace(text(plan, "namespaceId"));
            plan = core.find("DepositImportPlan", "id", id);
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
        try(var result=tx.execute("MATCH (transfer:DepositTransfer) WHERE transfer.state IN ['finished','aborted','expired'] AND EXISTS { MATCH (chunk:DepositTransferChunk {uploadId:transfer.id}) } RETURN transfer.id LIMIT 1",Map.of())){
            if(!result.hasNext())return 0;
        }
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
            if (upload != null && upload.hasProperty("writerIncarnation")
                    && ("aborted".equals(upload.getProperty("state")) || "uploading".equals(upload.getProperty("state")) && UploadBinding.abandoned(server,core,upload))) {
                cleanAbandonedResourceUpload(core,resources,loc,upload);
                tx.commit();
                return;
            }
            if (recovery && upload != null && !upload.hasProperty("writerIncarnation") && "uploading".equals(upload.getProperty("state"))) {
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

    private void cleanAbandonedResourceUpload(CoreRepository core, ResourceRepository resources, Node loc, Node upload) throws IOException {
        String id=CoreRepository.id(loc), uploadId=CoreRepository.id(upload), namespace=text(loc,"namespaceId");
        upload.setProperty("state","expired");
        upload.setProperty("writerFenced",true);
        upload.setProperty("expires",0L);
        loc.setProperty("leaseUntil",0L);
        if(!id.equals(upload.getProperty("resourceId")) || !namespace.equals(upload.getProperty("namespaceId"))
                || resources.resource(namespace,id)!=null || resources.protectedContent(loc)) {
            loc.setProperty("quarantined",true);
            loc.setProperty("diagnostic","Abandoned upload ownership or reachability requires verification");
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
            for (Node upload : core(tx).nodes("DepositCloneUpload", Map.of())) {
                if(upload.hasProperty("stagingPath")) knownStaging.add(text(upload,"stagingPath"));
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
