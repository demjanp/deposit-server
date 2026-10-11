package deposit.common;

import deposit.repository.*;
import static deposit.repository.ResourceRepository.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.time.ZonedDateTime;
import org.neo4j.graphdb.*;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/** Deposit-controlled maintenance; OCFL is a physical backend, never retention authority. */
public final class StorageManager implements AutoCloseable {
    public final ResourceFiles files=new ResourceFiles();
    public final ResourceStorage content;
    private final ServerContext server;private final GraphDatabaseAPI db;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"deposit-resource-maintenance");t.setDaemon(true);return t;});
    public volatile String diagnostic;
    public StorageManager(ServerContext server,GraphDatabaseAPI db){
        this.server=server;this.db=db;
        for(Path directory:List.of(db.databaseLayout().databaseDirectory(),db.databaseLayout().getTransactionLogsDirectory())) {
            Path path=directory.toAbsolutePath().normalize();
            for(Path root:List.of(files.root,files.exports))if(root.startsWith(path)||path.startsWith(root))throw new IllegalStateException("Storage overlaps database directories");
        }
        try{content=new OcflResourceStorage(files.root.resolve("ocfl"),files.root.resolve("ocfl-work"));}
        catch(java.io.IOException e){throw new IllegalStateException("Cannot initialize physical resource storage",e);}
    }
    private CoreRepository core(Transaction tx){return new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));}
    public void start(){worker.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS);}
    @Override public void close(){worker.shutdownNow();try{if(!worker.awaitTermination(10,TimeUnit.SECONDS))throw new IllegalStateException("Resource maintenance did not stop");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Resource shutdown interrupted",e);}content.close();}
    private List<String> ids(String label){try(var tx=db.beginTx()){return core(tx).nodes(label,Map.of()).stream().map(CoreRepository::id).toList();}}
    public void recover() {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(50);
        try(var tx=db.beginTx()){for(Node loc:core(tx).nodes("DepositResourceLocation",Map.of()))if(loc.hasProperty("path")||loc.hasProperty("suffix"))throw new IllegalStateException("Legacy resource records require fresh provisioning");}
        // Complete persisted purges before discovery validates any partially removed objects.
        for(String id:ids("DepositPruneItem")){collect(id);collect(id);}
        try{expireImportPlans();}catch(java.io.IOException e){throw new IllegalStateException("Cannot expire staged import plans",e);}
        for(String id:ids("DepositResourceLocation")) {
            if(System.nanoTime()>deadline)throw new IllegalStateException("Resource recovery exceeded startup bound");
            process(id,true);
        }
        discoverOrphans();
        try{content.recoverWork();}catch(java.io.IOException e){throw new IllegalStateException("Cannot reconcile resource work",e);}
    }
    private void tick(){try {expireImportPlans();for(String id:ids("DepositResourceLocation")){if(Thread.currentThread().isInterrupted())return;process(id,false);}for(String id:ids("DepositPruneItem"))collect(id);}catch(Exception e){diagnostic="Resource maintenance pending: "+e.getClass().getSimpleName();}}
    private void expireImportPlans()throws java.io.IOException {
        long now=server.sessionTime();List<String> expired=new ArrayList<>();
        try(var tx=db.beginTx()){for(Node plan:core(tx).nodes("DepositImportPlan",Map.of()))if(number(plan,"expiresAt",0)<=now)expired.add(CoreRepository.id(plan));}
        for(String id:expired)try(var tx=db.beginTx()){CoreRepository core=core(tx);Node plan=core.find("DepositImportPlan","id",id);if(plan==null||number(plan,"expiresAt",0)>now)continue;String path=plan.hasProperty("stagingPath")?text(plan,"stagingPath"):null;if(path!=null)Files.deleteIfExists(files.path(path));plan.delete();tx.commit();}
    }
    private void process(String id,boolean recovery) {
        try(var tx=db.beginTx()) {
            CoreRepository core=core(tx);ResourceRepository r=new ResourceRepository(tx,core);Node loc=r.location(id);if(loc==null)return;
            core.lockNamespace(text(loc,"namespaceId"));r.lock(id);
            if(Boolean.TRUE.equals(loc.getProperty("collected",false))||loc.hasProperty("deleting"))return;
            Node upload=loc.hasProperty("uploadId")?r.transfer(text(loc,"uploadId")):null;
            if(recovery&&upload!=null&&"uploading".equals(upload.getProperty("state"))) {
                Path staged=files.path(text(loc,"stagingPath"));long offset=number(upload,"nextOffset",0);
                if(Files.exists(staged))try(var channel=FileChannel.open(staged,StandardOpenOption.WRITE)){if(channel.size()<offset)throw new java.io.IOException("Committed upload content missing");channel.truncate(offset);channel.force(true);}
                else if(offset>0)throw new java.io.IOException("Committed upload content missing");
                if(content.exists(id))content.verify(r.content(loc));
            }
            r.expire(loc);
            if(Boolean.TRUE.equals(loc.getProperty("published",false))) {
                if(recovery)content.verify(r.content(loc));
                if(loc.hasProperty("stagingPath")) {
                    // This record was committed with ready metadata and the finish receipt.
                    content.verify(r.content(loc));
                    server.storagePoint("staging-cleanup-before");Files.deleteIfExists(files.path(text(loc,"stagingPath")));server.storagePoint("staging-cleanup-after");
                    loc.removeProperty("stagingPath");
                }
            }
            loc.removeProperty("diagnostic");tx.commit();
        }catch(Exception e){recordFailure(id,"Resource recovery pending: "+e.getClass().getSimpleName());if(recovery)throw new IllegalStateException("Cannot reconcile resource "+id,e);}
    }
    private void recordFailure(String id,String message) {
        diagnostic=message;
        try(var tx=db.beginTx()){CoreRepository core=core(tx);Node loc=core.find("DepositResourceLocation","id",id);if(loc!=null){core.lockNamespace(text(loc,"namespaceId"));loc.setProperty("diagnostic",message);}tx.commit();}catch(Exception ignored){}
    }
    private void collect(String itemId) {
        try(var tx=db.beginTx()) {
            CoreRepository core=core(tx);ResourceRepository r=new ResourceRepository(tx,core);Node item=core.find("DepositPruneItem","id",itemId);
            if(item==null||!Set.of("pending","deleting","failed").contains(text(item,"state")))return;
            String resource=text(item,"resourceId");Node loc=r.location(resource);Node job=core.find("DepositPruneJob","id",item.getProperty("jobId"));
            if(loc==null||Boolean.TRUE.equals(loc.getProperty("collected",false))) {item.setProperty("state","completed");tx.commit();return;}
            core.lockNamespace(text(loc,"namespaceId"));r.lock(resource);long age=number(job,"minimumAge",0);
            if("failed".equals(item.getProperty("state")))item.setProperty("state",itemId.equals(loc.getProperty("deleting",null))?"deleting":"pending");
            if(text(item,"state").equals("pending")) {
                if(!r.eligible(loc,age)){item.setProperty("state","skipped");tx.commit();return;}
                loc.setProperty("deleting",itemId);item.setProperty("state","deleting");item.setProperty("deletionBytes",physicalBytes(loc));tx.commit();return;
            }
            if(!itemId.equals(loc.getProperty("deleting",null)))throw new IllegalStateException("Collection intent mismatch");
            if(r.protectedContent(loc)||r.now()-number(loc,"orphanSince",r.now())<age) {loc.removeProperty("deleting");item.setProperty("state","skipped");tx.commit();return;}
            // The committed intent excludes attachment. The recorded size survives partial purge.
            server.storagePoint("collection-before");content.purge(resource);
            if(loc.hasProperty("stagingPath"))Files.deleteIfExists(files.path(text(loc,"stagingPath")));
            server.storagePoint("collection-after");
            if(content.exists(resource))throw new java.io.IOException("Resource purge incomplete");
            loc.setProperty("collected",true);loc.removeProperty("deleting");loc.removeProperty("stagingPath");loc.setProperty("leaseUntil",0L);
            Node publicResource=r.resource(text(loc,"namespaceId"),resource);if(publicResource!=null)publicResource.setProperty("state","collected");
            item.setProperty("reclaimedBytes",number(item,"deletionBytes",0));item.setProperty("state","completed");item.removeProperty("diagnostic");tx.commit();
        }catch(Exception e){
            diagnostic="Collection failed: "+e.getClass().getSimpleName();
            try(var tx=db.beginTx()){Node item=core(tx).find("DepositPruneItem","id",itemId);if(item!=null){item.setProperty("state","failed");item.setProperty("diagnostic",diagnostic);}tx.commit();}catch(Exception ignored){}
        }
    }
    private void discoverOrphans() {
        Set<String> knownIds=new HashSet<>(),knownStaging=new HashSet<>();
        try(var tx=db.beginTx()) {
            for(Node loc:core(tx).nodes("DepositResourceLocation",Map.of())) {knownIds.add(CoreRepository.id(loc));if(loc.hasProperty("stagingPath"))knownStaging.add(text(loc,"stagingPath").replace('\\','/'));}
            for(Node plan:core(tx).nodes("DepositImportPlan",Map.of()))if(number(plan,"expiresAt",0)>server.sessionTime()&&plan.hasProperty("stagingPath"))knownStaging.add(text(plan,"stagingPath").replace('\\','/'));
        }
        String unknown="00000000-0000-0000-0000-000000000000";
        try {
            for(var found:content.discover())if(!knownIds.contains(found.resourceId())) {
                try(var tx=db.beginTx()) {
                    CoreRepository core=core(tx);ResourceRepository r=new ResourceRepository(tx,core);
                    if(core.find("DepositNamespaceLock","id",unknown)==null)r.record("DepositNamespaceLock",unknown,Map.of());core.lockNamespace(unknown);
                    r.record("DepositResourceLocation",found.resourceId(),Json.map("namespaceId",unknown,"storedFilename",found.storedFilename(),"bytes",found.bytes(),"checksum",found.checksum(),"published",true,"orphanSince",server.sessionTime(),"leaseUntil",0L));tx.commit();
                }
            }
            try(var paths=Files.walk(files.root.resolve("staging"))) {
                for(Path path:paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList()) {
                    String relative=files.root.relativize(path).toString().replace('\\','/');files.path(relative);if(knownStaging.contains(relative))continue;
                    try(var tx=db.beginTx()) {
                        CoreRepository core=core(tx);ResourceRepository r=new ResourceRepository(tx,core);
                        if(core.find("DepositNamespaceLock","id",unknown)==null)r.record("DepositNamespaceLock",unknown,Map.of());core.lockNamespace(unknown);
                        r.record("DepositResourceLocation",UUID.randomUUID().toString(),Json.map("namespaceId",unknown,"stagingPath",relative,"bytes",Files.size(path),"checksum",ResourceFiles.checksum(path),"orphanSince",server.sessionTime(),"leaseUntil",0L));tx.commit();
                    }
                }
            }
        }catch(Exception e){throw new IllegalStateException("Cannot reconcile resource orphans",e);}
    }
    private long physicalBytes(Node loc)throws java.io.IOException {
        long bytes=content.physicalBytes(CoreRepository.id(loc));
        if(loc.hasProperty("stagingPath")){Path path=files.path(text(loc,"stagingPath"));if(Files.exists(path))bytes+=Files.size(path);}return bytes;
    }
}
