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

/** Durable maintenance. Every filesystem operation is recoverable from committed private records. */
public final class StorageManager implements AutoCloseable {
    public final ResourceFiles files=new ResourceFiles();
    private final ServerContext server;private final GraphDatabaseAPI db;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"deposit-resource-maintenance");t.setDaemon(true);return t;});
    public volatile String diagnostic;
    public StorageManager(ServerContext server,GraphDatabaseAPI db){
        this.server=server;this.db=db;
        for(Path directory:List.of(db.databaseLayout().databaseDirectory(),db.databaseLayout().getTransactionLogsDirectory())) {
            Path path=directory.toAbsolutePath().normalize();
            for(Path root:List.of(files.root,files.exports))if(root.startsWith(path)||path.startsWith(root))throw new IllegalStateException("Storage overlaps database directories");
        }
    }
    private CoreRepository core(Transaction tx){return new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));}
    public void start(){worker.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS);}
    @Override public void close(){worker.shutdownNow();try{worker.awaitTermination(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private List<String> ids(String label){try(var tx=db.beginTx()){return core(tx).nodes(label,Map.of()).stream().map(CoreRepository::id).toList();}}
    public void recover() {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(50);
        for(String id:ids("DepositResourceLocation")) {
            if(System.nanoTime()>deadline)throw new IllegalStateException("Resource recovery exceeded startup bound");
            process(id,true);process(id,false);
        }
        discoverOrphans();
        for(String id:ids("DepositPruneItem"))collect(id);
    }
    private void tick(){try {for(String id:ids("DepositResourceLocation")){if(Thread.currentThread().isInterrupted())return;process(id,true);process(id,false);}for(String id:ids("DepositPruneItem"))collect(id);}catch(Exception e){diagnostic="Resource maintenance pending: "+e.getClass().getSimpleName();}}
    private void process(String id,boolean prepare) {
        try(var tx=db.beginTx()) {
            CoreRepository core=core(tx);ResourceRepository r=new ResourceRepository(tx,core);Node loc=r.location(id);if(loc==null)return;
            core.lockNamespace(text(loc,"namespaceId"));r.lock(id);
            if(Boolean.TRUE.equals(loc.getProperty("collected",false))||loc.hasProperty("deleting"))return;
            if(prepare) {
                Node upload=loc.hasProperty("uploadId")?r.transfer(text(loc,"uploadId")):null;
                if(upload!=null&&"uploading".equals(upload.getProperty("state"))) {
                    Path staged=files.path(text(loc,"path"));long offset=number(upload,"nextOffset",0);
                    if(Files.exists(staged))try(var channel=FileChannel.open(staged,StandardOpenOption.WRITE)){if(channel.size()<offset)loc.setProperty("diagnostic","Committed upload content missing");else {channel.truncate(offset);channel.force(true);}}
                    // Publication may precede a rolled-back ready-record commit.
                    String published="live/"+text(loc,"suffix");if(Files.exists(files.path(published)))files.verify(published,number(loc,"bytes",0),text(loc,"checksum"));
                }
                r.expireAndPlace(loc);
            } else r.movePending(loc);
            tx.commit();
        }catch(Exception e){recordFailure(id,"Resource recovery pending: "+e.getClass().getSimpleName());}
    }
    private void recordFailure(String id,String message) {
        diagnostic=message;
        try(var tx=db.beginTx()){CoreRepository core=core(tx);Node loc=core.find("DepositResourceLocation","id",id);if(loc!=null){core.lockNamespace(text(loc,"namespaceId"));loc.setProperty("diagnostic",message);}tx.commit();}catch(Exception ignored){}
    }
    private void collect(String itemId) {
        String resource=null;
        try(var tx=db.beginTx()) {
            CoreRepository core=core(tx);ResourceRepository r=new ResourceRepository(tx,core);Node item=core.find("DepositPruneItem","id",itemId);
            if(item==null||!Set.of("pending","deleting","failed").contains(text(item,"state")))return;
            resource=text(item,"resourceId");Node loc=r.location(resource);Node job=core.find("DepositPruneJob","id",item.getProperty("jobId"));
            if(loc==null||Boolean.TRUE.equals(loc.getProperty("collected",false))) {item.setProperty("state","completed");tx.commit();return;}
            core.lockNamespace(text(loc,"namespaceId"));r.lock(resource);long age=number(job,"minimumAge",0);
            if("failed".equals(item.getProperty("state")))item.setProperty("state",itemId.equals(loc.getProperty("deleting",null))?"deleting":"pending");
            if(text(item,"state").equals("pending")) {
                if(!r.eligible(loc,age)){item.setProperty("state","skipped");tx.commit();return;}
                loc.setProperty("deleting",itemId);item.setProperty("state","deleting");item.setProperty("deletionBytes",physicalBytes(loc));tx.commit();return;
            }
            if(!itemId.equals(loc.getProperty("deleting",null)))throw new IllegalStateException("Collection intent mismatch");
            if(r.protectedContent(loc)||r.now()-number(loc,"orphanSince",r.now())<age) {loc.removeProperty("deleting");item.setProperty("state","skipped");tx.commit();return;}
            // The committed deleting intent excludes new attachment. Missing bytes here mean
            // an interrupted unlink; the recorded byte count is applied only at completion.
            Set<String> paths=new HashSet<>();paths.add(text(loc,"path"));if(loc.hasProperty("moveTo"))paths.add(text(loc,"moveTo"));
            if(loc.hasProperty("suffix")){paths.add("live/"+text(loc,"suffix"));paths.add("deleted/"+text(loc,"suffix"));}
            server.storagePoint("collection-before");for(String path:paths)Files.deleteIfExists(files.path(path));server.storagePoint("collection-after");
            loc.setProperty("collected",true);loc.removeProperty("deleting");loc.removeProperty("moveTo");loc.setProperty("leaseUntil",0L);
            Node publicResource=r.resource(text(loc,"namespaceId"),resource);if(publicResource!=null)publicResource.setProperty("state","collected");
            item.setProperty("reclaimedBytes",number(item,"deletionBytes",0));item.setProperty("state","completed");item.removeProperty("diagnostic");tx.commit();
        }catch(Exception e){
            diagnostic="Collection failed: "+e.getClass().getSimpleName();
            try(var tx=db.beginTx()){Node item=core(tx).find("DepositPruneItem","id",itemId);if(item!=null){item.setProperty("state","failed");item.setProperty("diagnostic",diagnostic);}tx.commit();}catch(Exception ignored){}
        }
    }
    private void discoverOrphans() {
        Set<String> known=new HashSet<>();
        try(var tx=db.beginTx()) {
            for(Node loc:core(tx).nodes("DepositResourceLocation",Map.of())) {known.add(text(loc,"path").replace('\\','/'));if(loc.hasProperty("suffix")){known.add("live/"+text(loc,"suffix"));known.add("deleted/"+text(loc,"suffix"));}if(loc.hasProperty("moveTo"))known.add(text(loc,"moveTo"));}
        }
        try(var paths=Files.walk(files.root)) {
            for(Path path:paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList()) {
                String relative=files.root.relativize(path).toString().replace('\\','/');files.path(relative);if(known.contains(relative))continue;
                String[] parts=relative.split("/");String namespace=parts.length>=3?parts[1]:"00000000-0000-0000-0000-000000000000";
                try{namespace=RequestContext.uuid(namespace);}catch(RuntimeException e){namespace="00000000-0000-0000-0000-000000000000";}
                try(var tx=db.beginTx()) {
                    CoreRepository core=core(tx);ResourceRepository r=new ResourceRepository(tx,core);
                    if(core.find("DepositNamespaceLock","id",namespace)==null)r.record("DepositNamespaceLock",namespace,Map.of());core.lockNamespace(namespace);
                    r.record("DepositResourceLocation",UUID.randomUUID().toString(),Json.map("namespaceId",namespace,"path",relative,"bytes",Files.size(path),"checksum",ResourceFiles.checksum(path),"orphanSince",server.sessionTime(),"leaseUntil",0L));tx.commit();
                }
            }
        }catch(Exception e){throw new IllegalStateException("Cannot reconcile resource orphans",e);}
    }
    private long physicalBytes(Node loc)throws java.io.IOException {
        Set<String> paths=new HashSet<>();paths.add(text(loc,"path"));if(loc.hasProperty("moveTo"))paths.add(text(loc,"moveTo"));if(loc.hasProperty("suffix")){paths.add("live/"+text(loc,"suffix"));paths.add("deleted/"+text(loc,"suffix"));}
        long bytes=0;for(String path:paths)if(Files.exists(files.path(path)))bytes+=Files.size(files.path(path));return bytes;
    }
}
