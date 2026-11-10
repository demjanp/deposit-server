package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageAdmissionTest {
    @TempDir Path root;

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
}
