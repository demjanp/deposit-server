package deposit.common;

import com.sun.jna.Pointer;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageAdmissionTest {
    @TempDir Path root;

    @Test void concurrentCapacityChecksFailFastInsteadOfWaitingUnderDomainLocks()throws Exception {
        var insideCapacityCheck=new CountDownLatch(1);
        var releaseCapacityCheck=new CountDownLatch(1);
        var volumes=new StorageVolumeIdentity(new StorageVolumeIdentity.WindowsApi(){
            @Override public StorageVolumeIdentity.Handle openDirectory(String path){return new StorageVolumeIdentity.Handle(Pointer.createConstant(1));}
            @Override public String finalPath(StorageVolumeIdentity.Handle handle,int mode)throws java.io.IOException {
                if(Thread.currentThread().getName().equals("admission-A")&&mode==1){
                    insideCapacityCheck.countDown();
                    try {
                        if(!releaseCapacityCheck.await(10,TimeUnit.SECONDS))throw new java.io.IOException("Test capacity check was not released");
                    }catch(InterruptedException failure){Thread.currentThread().interrupt();throw new java.io.IOException("Test capacity check interrupted",failure);}
                }
                return mode==1?"\\\\?\\Volume{01234567-89ab-cdef-0123-456789abcdef}\\":"\\Device\\HarddiskVolume1\\nested";
            }
            @Override public void close(StorageVolumeIdentity.Handle handle){}
        });
        var threadNumber=new AtomicInteger();
        var pool=Executors.newFixedThreadPool(3,task->new Thread(task,"admission-"+(char)('A'+threadNumber.getAndIncrement())));
        try(var admission=new StorageAdmission(root,"run-1",volumes)){
            var existing=admission.acquire("existing","resource-upload",Map.of(root,1L));
            try {
                var checking=pool.submit(()->admission.acquire("checking","resource-upload",Map.of(root,1L)));
                assertTrue(insideCapacityCheck.await(10,TimeUnit.SECONDS),"first admission did not enter capacity check");
                var growing=pool.submit(()->{
                    try{admission.grow(existing,Map.of(root,1L));return false;}
                    catch(StorageAdmission.StoragePressureException expected){return "Storage admission is busy".equals(expected.getMessage());}
                });
                var acquiring=pool.submit(()->{
                    try{admission.acquire("new","resource-upload",Map.of(root,1L));return false;}
                    catch(StorageAdmission.StoragePressureException expected){return "Storage admission is busy".equals(expected.getMessage());}
                });
                assertTrue(growing.get(250,TimeUnit.MILLISECONDS),"grow must report busy immediately");
                assertTrue(acquiring.get(250,TimeUnit.MILLISECONDS),"acquire must report busy immediately");
                releaseCapacityCheck.countDown();
                var checked=checking.get(10,TimeUnit.SECONDS);
                admission.grow(existing,Map.of(root,1L));
                var acquired=admission.acquire("new","resource-upload",Map.of(root,1L));
                admission.close(checked);
                admission.close(acquired);
                admission.close(existing);
            }finally{releaseCapacityCheck.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
        }finally{pool.shutdownNow();}
    }

    @Test void acquireGrowMaterializeReleaseAndCloseUseFutureBytesOnly()throws Exception {
        try(var admission=new StorageAdmission(root,"run-1")){
            var reservation=admission.acquire("upload","upload-1","op-1","resource-upload",Map.of(root,128L),Map.of("stagingPath","staging/upload.part"));
            String volume=admission.volumeId(root);assertEquals(128L+64*1024,reservation.commitments().get(volume));
            admission.grow(reservation,Map.of(root,64L));assertEquals(128L+64*1024+64,reservation.commitments().get(volume));
            admission.materialized(reservation,root,64L);admission.release(reservation,Map.of(root,32L));
            assertEquals(128L+64*1024-32,reservation.commitments().get(volume));
            admission.close(reservation);assertFalse(java.nio.file.Files.exists(root.resolve("admission").resolve(reservation.id+".json")));
        }
    }

    @Test void restartReconciliationUsesAuthoritativeOwnerStateAndPreservesUncertainOwners()throws Exception {
        String id,volume;
        try(var first=new StorageAdmission(root,"old-run")){
            var reservation=first.acquire("upload","upload-2",null,"resource-upload",Map.of(root,512L),Map.of("stagingPath","staging/upload.part"));id=reservation.id;volume=first.volumeId(root);
            var saved=Json.read(java.nio.file.Files.readString(root.resolve("admission").resolve(id+".json")));assertFalse(saved.containsKey("format"));assertFalse(saved.containsKey("version"));
        }
        try(var restarted=new StorageAdmission(root,"new-run")){
            restarted.reconcile(reservation->new StorageAdmission.OwnerState(true,true,Map.of(volume,256L),Map.of("stagingPath","staging/upload.part","nextOffset",256L)));
            var resumed=restarted.resume(id);assertEquals("new-run",resumed.serverRunId());assertEquals(256L,resumed.commitments().get(volume));
            restarted.reconcile(reservation->StorageAdmission.OwnerState.uncertain());assertTrue(java.nio.file.Files.exists(root.resolve("admission").resolve(id+".json")));
            restarted.close(resumed);
        }
    }

    @Test void insufficientSpaceFailsWithoutInstallingAReservation()throws Exception {
        try(var admission=new StorageAdmission(root,"run-1")){
            assertThrows(StorageAdmission.StoragePressureException.class,()->admission.acquire("upload","huge",null,"resource-upload",Map.of(root,Long.MAX_VALUE),Map.of()));
            try(var records=java.nio.file.Files.list(root.resolve("admission"))){assertEquals(0,records.count());}
        }
    }

    @Test void publicationKeepsCreditForResourcesThatHaveNotBeenStagedYet()throws Exception {
        try(var admission=new StorageAdmission(root,"run-1")){
            var reservation=admission.acquire("import-apply","import-1","op-1","resource-expansion",Map.of(root,17L),Map.of());
            admission.materialized(reservation,root,10L); // first ten-byte staging file exists
            admission.grow(reservation,Map.of(root,20L)); // work copy plus final OCFL object
            admission.materialized(reservation,root,10L); // final immutable object now exists
            admission.release(reservation,Map.of(root,10L)); // temporary OCFL work copy was cleaned
            assertEquals(64L*1024+7L,reservation.commitments().get(admission.volumeId(root)),
                "the second resource's staging reservation must remain available");
            admission.materialized(reservation,root,7L);
            assertEquals(64L*1024L,reservation.commitments().getOrDefault(admission.volumeId(root),0L));
            admission.close(reservation);
        }
    }

    @Test void importReservationsAreIsolatedByActorAndReceiptScope()throws Exception {
        try(var admission=new StorageAdmission(root,"run-1")){
            String operation="same-operation-id";
            String firstOwner=ImportWriteBudget.reservationOwnerId("namespace-a","actor-a",operation);
            String secondOwner=ImportWriteBudget.reservationOwnerId("namespace-b","actor-b",operation);
            assertNotEquals(firstOwner,secondOwner);
            var first=admission.acquire("import-write",firstOwner,operation,"database-result",Map.of(root,100L),Map.of("receiptScope","namespace-a","actorId","actor-a"));
            var second=admission.acquire("import-write",secondOwner,operation,"database-result",Map.of(root,200L),Map.of("receiptScope","namespace-b","actorId","actor-b"));
            assertNotEquals(first.id,second.id);
            admission.close(first);
            assertEquals(64L*1024+200L,second.commitments().get(admission.volumeId(root)));
            admission.close(second);
        }
    }
}
