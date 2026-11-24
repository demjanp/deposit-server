package deposit.common;

import static deposit.repository.ResourceRepository.*;
import deposit.repository.CoreRepository;
import deposit.repository.ResourceRepository;
import deposit.repository.ResourceTransferStore.Cleanup;
import deposit.repository.ResourceTransferStore.Transfer;
import deposit.repository.PruneRepository.Item;
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
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/** Standalone storage maintenance transactions and filesystem reconciliation jobs. */
final class StorageMaintenanceJobs {
    private final ServerContext server;
    private final GraphDatabaseAPI db;
    private final ResourceFiles files;
    private final Supplier<ResourceStorage> content;
    private final Consumer<String> diagnostic;
    private String exchangeCursor="",planCursor="",quarantineCursor="";

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
        long now=server.sessionTime();
        var tables=server.storage.tables;
        for(String id:tables.expired(now))try(var tx=db.beginTx()) {
            var core=core(tx);
            var table=tables.table(id);
            if(table==null)continue;
            core.lockNamespace(table.namespace());
            table=tables.table(id);
            if(table==null||table.expires()>now)continue;
            var resources=new ResourceRepository(tx,core);
            resources.lock(table.resource());
            Files.deleteIfExists(files.path(table.path()));
            Files.deleteIfExists(files.path(table.rowIndexPath()));
            server.storage.importPlans.clearTable(id);
            tables.delete(id);
            Node resource=resources.completed(table.resource());
            if(resource!=null)resources.orphan(resource);
            tx.commit();
        }
    }

    void expireExchange() throws IOException {
        long now=server.sessionTime();
        var exchange=server.storage.exchange;
        var candidates=new LinkedHashSet<>(exchange.cleanupCandidates(now));
        var writers=exchange.uploading(exchangeCursor);
        exchangeCursor="";
        for(var writer:writers) {
            if(UploadBinding.abandonedUpload(server,writer.id(),writer.writerBound()))
                candidates.add(writer.id());
            if(candidates.size()==128) {
                exchangeCursor=writer.id();
                break;
            }
        }
        if(exchangeCursor.isEmpty()&&writers.size()==128)exchangeCursor=writers.getLast().id();
        for(String id:candidates)cleanExchange(id,now);
    }

    private void cleanExchange(String id,long now)throws IOException {
        var exchange=server.storage.exchange;
        try(var tx=db.beginTx()) {
            var kernel=((org.neo4j.kernel.impl.coreapi.InternalTransaction)tx).kernelTransaction();
            server.lockExchange(kernel,id);
            var stage=exchange.stage(id);
            if(stage==null)return;
            var core=core(tx);
            if("uploading".equals(stage.state()) && (stage.expiresAt()<=now
                    || UploadBinding.abandonedUpload(server,id,stage.writerBound()))) {
                stage=stage.cleanup("aborted",now,now+ResourceFiles.LEASE,false,false);
                exchange.save(stage,"");
            }
            boolean removing=stage.cleanupAfter()<=now && !server.storage.importPlans.protectsPackage(id,now);
            if(removing && !stage.cleaned() || stage.inputCleanup()) {
                String path="staging/exchange/"+RequestContext.uuid(id);
                files.deleteOwnedStagingTree(removing?path:path+"/input");
                boolean done=exchange.deleteMembers(id)<256;
                if(done&&removing)done=server.storage.operations.cleanUpload(id,true);
                stage=stage.cleanup(stage.state(),stage.cleanupAfter(),stage.removeAfter(),!done && stage.inputCleanup(),
                    removing && done);
                if(!"uploading".equals(stage.state())) server.stagingWriters.remove(id);
                exchange.save(stage,"");
            }
            if(stage.cleaned() && stage.removeAfter()<=now) {
                server.storage.admission.closeIfPresent(stage.admissionId());
                exchange.deleteStage(id);
            }
            tx.commit();
        }
    }

    void expireImportResults() {
        long cutoff=server.sessionTime()-ImportResultContract.READER;
        var store=server.storage.importResults;
        String id=store.expired(cutoff);
        if(id==null)return;
        try(var tx=db.beginTx()) {
            var kernel=((org.neo4j.kernel.impl.coreapi.InternalTransaction)tx).kernelTransaction();
            server.lockImportResult(kernel,id);
            var result=store.collectable(id);
            if(result!=null && result.expires()<=cutoff && store.deleteSegments(id))
                store.deleteRoot(result);
            tx.commit();
        }
    }

    void expireImportPlans() throws IOException {
        long now=server.sessionTime();
        var plans=server.storage.importPlans;
        var candidates=new LinkedHashSet<>(plans.cleanupCandidates(now));
        if(candidates.size()<128) {
            var bound=plans.boundPlans(planCursor);
            planCursor="";
            for(String id:bound) {
                if(UploadBinding.stagingAbandoned(server,id,true))candidates.add(id);
                if(candidates.size()==128) {
                    planCursor=id;
                    break;
                }
            }
            if(planCursor.isEmpty()&&bound.size()==128)planCursor=bound.getLast();
        }
        for(String id:candidates)try(var tx=db.beginTx()) {
            var plan=plans.plan(id);
            if(plan==null)continue;
            core(tx).lockNamespace(plan.namespace());
            plan=plans.plan(id);
            if(plan==null)continue;
            boolean expired=plan.expires()<=now;
            boolean ready="ready".equals(plan.state());
            boolean abandoned=ready && UploadBinding.stagingAbandoned(server,id,plan.writerBound());
            if(!expired && ready && !abandoned)continue;
            if(abandoned && !expired)plans.save(plan.transition("cancelled",plan.expires()),"");
            if(plan.stagingPath()!=null)Files.deleteIfExists(files.path(plan.stagingPath()));
            if(plan.admissionId()!=null)server.storage.admission.closeIfPresent(plan.admissionId());
            if(expired)plans.delete(id);
            else plans.clearStaging(id);
            UploadBinding.forgetStaging(server,id);
            tx.commit();
        }
    }

    void process(String id,boolean recovery) {
        try(var tx=db.beginTx()) {
            var core=core(tx);
            var resources=new ResourceRepository(tx,core);
            var resource=resources.state(id);
            if(!resource.exists())return;
            core.lockNamespace(resource.namespace());
            resources.lock(id);
            resource=resources.state(id);
            if(!resource.exists()||resource.deleting()!=null)return;
            var transfer=resource.transfer();
            if(resource.provisional()!=null) {
                if(transfer.collected()||transfer.quarantined())return;
                if(transfer.writerBound() && ("aborted".equals(transfer.state())||"expired".equals(transfer.state())
                        || "uploading".equals(transfer.state())&&UploadBinding.abandonedUpload(server,transfer.id(),true))) {
                    cleanAbandonedUpload(resources,transfer);
                    tx.commit();
                    return;
                }
            }
            resources.expire(id);
            var completed=resource.completed();
            if(completed!=null) {
                if(recovery||transfer!=null&&transfer.stagingPath()!=null)content.get().verify(resources.content(completed));
                if(transfer!=null&&transfer.stagingPath()!=null) {
                    server.storagePoint("staging-cleanup-before");
                    deleteUploadStaging(transfer);
                    server.storagePoint("staging-cleanup-after");
                    server.storage.transfers.save(transfer.cleanup(null,null,false,false,null),"");
                    UploadBinding.forgetStaging(server,transfer.id());
                }
                completed.removeProperty("diagnostic");
            }
            tx.commit();
        } catch(Exception failure) {
            recordFailure(id,"Resource recovery pending: "+failure.getClass().getSimpleName());
            if(recovery)throw new IllegalStateException("Cannot reconcile resource "+id,failure);
        }
    }

    private void cleanAbandonedUpload(ResourceRepository resources,Transfer transfer)throws IOException {
        String id=transfer.resource();
        transfer=transfer.progress(transfer.nextOffset(),"expired",0,transfer.admissionId(),transfer.orphanSince());
        server.storage.transfers.save(transfer,"");
        if(resources.completed(id)!=null||resources.protectedContent(resources.state(id))) {
            String message="Abandoned upload ownership or reachability requires verification";
            server.storage.transfers.save(transfer.cleanup(transfer.stagingPath(),null,false,true,message),"");
            diagnostic.accept(message);
            server.storage.operations.cleanUpload(transfer.id(),false);
            return;
        }
        String expected="staging/"+transfer.namespace()+"/"+transfer.id()+".part";
        if(transfer.stagingPath()!=null&&!expected.equals(transfer.stagingPath())) {
            String message="Abandoned upload staging ownership requires verification";
            server.storage.transfers.save(transfer.cleanup(transfer.stagingPath(),null,false,true,message),"");
            server.storage.operations.cleanUpload(transfer.id(),false);
            diagnostic.accept(message);
            return;
        }
        deleteUploadStaging(transfer);
        if(content.get().exists(id)) {
            content.get().verify(transfer.content());
            content.get().purge(id);
        }
        if(content.get().exists(id))throw new IOException("Abandoned upload purge incomplete");
        if(transfer.admissionId()!=null)server.storage.admission.closeIfPresent(transfer.admissionId());
        server.storage.transfers.save(transfer.cleanup(null,null,true,false,null),"");
        server.storage.operations.cleanUpload(transfer.id(),true);
        UploadBinding.forgetStaging(server,transfer.id());
    }

    private void deleteUploadStaging(Transfer transfer)throws IOException {
        if(transfer.stagingPath()==null)return;
        String expected="staging/"+transfer.namespace()+"/"+transfer.id()+".part";
        if(!expected.equals(transfer.stagingPath()))throw new IOException("Upload staging ownership is unclear");
        Files.deleteIfExists(files.path(expected));
    }

    private void recordFailure(String id,String message) {
        diagnostic.accept(message);
        try(var tx=db.beginTx()) {
            var core=core(tx);
            var resources=new ResourceRepository(tx,core);
            var resource=resources.state(id);
            if(!resource.exists())return;
            core.lockNamespace(resource.namespace());
            resources.lock(id);
            resource=resources.state(id);
            if(resource.completed()!=null)resource.completed().setProperty("diagnostic",message);
            else if(resource.provisional()!=null){
                var transfer=resource.provisional();
                server.storage.transfers.save(transfer.cleanup(transfer.stagingPath(),transfer.deleting(),
                    transfer.collected(),transfer.quarantined(),message),"");
            }
            tx.commit();
        }catch(Exception ignored){ }
    }

    void collect(String itemId) {
        var pruning=server.storage.pruning;
        Item item=pruning.item(itemId);
        if(!actionable(item))return;
        StorageAdmission.Reservation reservation;
        try {
            reservation=server.storage.admission.acquire("maintenance-prune",itemId,null,"prune-maintenance",
                Map.of(files.root,64L*1024),Json.map("phase","prepare","itemId",itemId));
        }catch(IOException|StorageAdmission.StoragePressureException pressure){
            diagnostic.accept("Collection admission pending: "+pressure.getClass().getSimpleName());
            return;
        }
        boolean resolved=false;
        try(var tx=db.beginTx()) {
            var core=core(tx);
            var resources=new ResourceRepository(tx,core);
            String id=item.resource();
            var resource=resources.state(id);
            if(resource.exists())core.lockNamespace(resource.namespace());
            resources.lock(id);
            item=pruning.item(itemId);
            if(!actionable(item)) {
                resolved=true;
                return;
            }
            resource=resources.state(id);
            var transfer=resource.transfer();
            long now=resources.now();
            if(!resource.exists()||resource.provisional()!=null&&resource.provisional().collected()) {
                if(content.get().exists(id)||transfer!=null&&transfer.stagingPath()!=null
                        && Files.exists(files.path(transfer.stagingPath()),LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Missing collection owner with retained physical data");
                pruning.transition(item,"completed",item.deletionBytes(),null,now);
                resolved=true;
                return;
            }
            var job=pruning.job(item.job());
            String deleting=resource.deleting();
            if(!itemId.equals(deleting)) {
                if(!server.runId.equals(job.incarnation()) || !resources.eligible(resource,job.minimumAge())) {
                    pruning.transition(item,"skipped",null,null,now);
                    resolved=true;
                    return;
                }
                long bytes=physicalBytes(id,transfer==null?null:transfer.stagingPath());
                pruning.transition(item,"deleting",bytes,null,now);
                if(resource.completed()!=null)resource.completed().setProperty("deleting",itemId);
                else server.storage.transfers.save(transfer.cleanup(transfer.stagingPath(),itemId,false,false,null),"");
                tx.commit();
                resolved=true;
                return;
            }
            if(item.deletionBytes()==null)throw new IllegalStateException("Collection intent lacks physical scope");
            if(resources.protectedContent(resource)||resource.orphanSince()==null
                    ||now-resource.orphanSince()<job.minimumAge()) {
                if(resource.completed()!=null)resource.completed().removeProperty("deleting");
                else server.storage.transfers.save(transfer.cleanup(transfer.stagingPath(),null,false,false,null),"");
                tx.commit();
                pruning.transition(item,"skipped",null,null,now);
                resolved=true;
                return;
            }
            server.storagePoint("collection-before");
            content.get().purge(id);
            if(transfer!=null)deleteUploadStaging(transfer);
            server.storagePoint("collection-after");
            if(content.get().exists(id))throw new IOException("Resource purge incomplete");
            if(transfer!=null) {
                if(transfer.admissionId()!=null)server.storage.admission.closeIfPresent(transfer.admissionId());
                server.storage.transfers.save(transfer.cleanup(null,null,true,false,null),"");
                server.storage.operations.cleanUpload(transfer.id(),true);
                UploadBinding.forgetStaging(server,transfer.id());
            }
            if(resource.completed()!=null) {
                try(var edges=resource.completed().getRelationships()){
                    for(Relationship edge:edges)edge.delete();
                }
                resource.completed().delete();
            }
            tx.commit();
            pruning.transition(item,"completed",item.deletionBytes(),null,now);
            resolved=true;
        }catch(Exception failure) {
            String message="Collection failed: "+failure.getClass().getSimpleName();
            diagnostic.accept(message);
            try {
                var current=pruning.item(itemId);
                if(actionable(current))pruning.transition(current,"failed",current.deletionBytes(),message,server.sessionTime());
            }catch(RuntimeException ignored){ }
        }finally{
            if(resolved)try{server.storage.admission.close(reservation);}
            catch(IOException failure){diagnostic.accept("Collection admission cleanup pending");}
        }
    }

    private static boolean actionable(Item item) {
        return item!=null && Set.of("pending","deleting","failed").contains(item.state());
    }

    void expireTransfers() throws IOException {
        long now=server.sessionTime();
        var transfers=server.storage.transfers;
        for(String id:transfers.expired(now))try(var tx=db.beginTx()) {
            var transfer=transfers.transfer(id);
            if(transfer==null)continue;
            var core=core(tx);
            core.lockNamespace(transfer.namespace());
            new ResourceRepository(tx,core).lock(transfer.resource());
            transfer=transfers.transfer(id);
            if(transfer==null||transfer.expires()>now||transfer.stagingPath()!=null||transfer.quarantined()
                    ||transfer.deleting()!=null||!transfer.collected()&&!"finished".equals(transfer.state()))continue;
            server.storage.operations.cleanUpload(id,true);
            if(transfer.admissionId()!=null)server.storage.admission.closeIfPresent(transfer.admissionId());
            UploadBinding.forgetStaging(server,id);
            transfers.delete(id);
            tx.commit();
        }
    }

    void discoverOrphans() {
        Set<String> knownIds=new HashSet<>(),knownStaging=new HashSet<>();
        var transfers=server.storage.transfers;
        try(var tx=db.beginTx()){
            for(Node resource:core(tx).nodes("DResource",Map.of()))knownIds.add(CoreRepository.id(resource));
        }
        for(var transfer:transfers.scoped(null)){
            knownIds.add(transfer.resource());
            if(transfer.stagingPath()!=null)knownStaging.add(transfer.stagingPath());
        }
        knownStaging.addAll(server.storage.importPlans.stagingPaths());
        knownStaging.addAll(server.storage.tables.stagingPaths());
        final String unknown="00000000-0000-0000-0000-000000000000";
        try {
            for(var orphan:transfers.quarantined("",-1)) {
                if(orphan.stagingPath()!=null){
                    if(!Files.notExists(files.path(orphan.stagingPath()),LinkOption.NOFOLLOW_LINKS))knownStaging.add(orphan.stagingPath());
                    else transfers.clearQuarantine(orphan.id());
                }else if(content.get().exists(orphan.id()))knownIds.add(orphan.id());
                else transfers.clearQuarantine(orphan.id());
            }
            for(var found:content.get().discover())if(!knownIds.contains(found.resourceId()))
                transfers.quarantine(new Cleanup(found.resourceId(),unknown,found.storedFilename(),null,found.bytes(),
                    found.checksum(),server.sessionTime()));
            try(var paths=Files.walk(files.root.resolve("staging"))) {
                for(Path path:paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList()) {
                    String relative=files.root.relativize(path).toString().replace('\\','/');
                    if(knownStaging.contains(relative) || relative.startsWith("staging/exchange/")
                            && server.storage.exchange.hasStage(relative.split("/",4)[2]))continue;
                    files.path(relative);
                    transfers.quarantine(new Cleanup(UUID.randomUUID().toString(),unknown,null,relative,
                        Files.size(path),ResourceFiles.checksum(path),server.sessionTime()));
                }
            }
        }catch(Exception failure){throw new IllegalStateException("Cannot reconcile resource orphans",failure);}
    }

    void expireQuarantine() throws IOException {
        var transfers=server.storage.transfers;
        var records=transfers.quarantined(quarantineCursor,128);
        for(var orphan:records) {
            boolean absent=orphan.stagingPath()==null?!content.get().exists(orphan.id()):
                Files.notExists(files.path(orphan.stagingPath()),LinkOption.NOFOLLOW_LINKS);
            if(absent)transfers.clearQuarantine(orphan.id());
        }
        quarantineCursor=records.size()==128?records.getLast().id():"";
    }

    private long physicalBytes(String resource,String stagingPath)throws IOException {
        long bytes=content.get().physicalBytes(resource);
        if(stagingPath==null)return bytes;
        Path path=files.path(stagingPath);
        if(!Files.exists(path))return bytes;
        try{return Math.addExact(bytes,Files.size(path));}
        catch(ArithmeticException overflow){throw new IOException("Resource physical size is not representable",overflow);}
    }
}
