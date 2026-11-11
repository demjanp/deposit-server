package deposit.common;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Dynamic future-space commitments shared by all Server storage writers. */
public final class StorageAdmission implements AutoCloseable {
    private static final int MAX_RECORD_BYTES=64*1024;
    private static final long BOOKKEEPING_BYTES=MAX_RECORD_BYTES;
    private final Path root,directory;
    private final String serverRunId;
    private final StorageVolumeIdentity volumes;
    private final Map<String,Reservation> reservations=new ConcurrentHashMap<>();
    private final ReentrantLock stateLock=new ReentrantLock();
    private final Map<String,ReentrantLock> volumeLocks=new ConcurrentHashMap<>();

    public StorageAdmission(Path privateResourceRoot,String serverRunId)throws IOException {this(privateResourceRoot,serverRunId,new StorageVolumeIdentity());}
    StorageAdmission(Path privateResourceRoot,String serverRunId,StorageVolumeIdentity volumes)throws IOException {
        this.root=privateResourceRoot.toRealPath();this.directory=root.resolve("admission");this.serverRunId=Objects.requireNonNull(serverRunId);this.volumes=Objects.requireNonNull(volumes);
        Files.createDirectories(directory);if(Files.isSymbolicLink(directory)||!directory.toRealPath().equals(directory)||!volumes.resolve(directory).deviceId().equals(volumes.resolve(root).deviceId()))throw new IOException("Invalid private storage admission directory");
        load();
    }

    public Reservation acquire(String ownerId,String purpose,Map<Path,Long> commitments)throws IOException {
        return acquire("operation",ownerId,null,purpose,commitments,Map.of());
    }
    public Reservation acquire(String ownerKind,String ownerId,String operationId,String purpose,Map<Path,Long> commitments,Map<String,Object> writeIntent)throws IOException {
        lockForAdmission();try{
        if(ownerKind==null||ownerKind.isBlank()||ownerId==null||ownerId.isBlank()||purpose==null||purpose.isBlank())throw new IllegalArgumentException("Reservation ownership is required");
        for(Reservation current:reservations.values())if(!current.closed&&current.ownerKind.equals(ownerKind)&&current.ownerId.equals(ownerId)&&Objects.equals(current.operationId,operationId)&&current.purpose.equals(purpose)&&serverRunId.equals(current.serverRunId))return current;
        String id=UUID.randomUUID().toString();var ref=new LinkedHashMap<String,VolumeRef>();var reserved=new LinkedHashMap<String,Long>();
        for(var commitment:commitments.entrySet()){long bytes=commitment.getValue();if(bytes<0)throw new IllegalArgumentException("Negative storage commitment");StorageVolumeIdentity.Identity identity=volumes.resolve(commitment.getKey());ref.put(identity.deviceId(),VolumeRef.from(identity));reserved.merge(identity.deviceId(),bytes,StorageAdmission::addExact);}
        StorageVolumeIdentity.Identity metadataVolume=volumes.resolve(directory);ref.putIfAbsent(metadataVolume.deviceId(),VolumeRef.from(metadataVolume));
        reserved.merge(metadataVolume.deviceId(),BOOKKEEPING_BYTES,StorageAdmission::addExact);
        var lockIds=sorted(ref.keySet());withLocks(lockIds,()->checkCapacity(ref,reserved,null));
        var reservation=new Reservation(id,ownerKind,ownerId,operationId,purpose,serverRunId,ref,reserved,new LinkedHashMap<>(writeIntent),false);
        persist(reservation);reservations.put(id,reservation);return reservation;
        }finally{stateLock.unlock();}
    }

    public Reservation resume(String id)throws IOException {
        Reservation reservation=reservations.get(id);if(reservation==null)throw new IOException("Storage reservation is unavailable");
        if(!serverRunId.equals(reservation.serverRunId))throw new IOException("Storage reservation requires restart reconciliation");return reservation;
    }
    public String volumeId(Path path)throws IOException{return volumes.resolve(path).deviceId();}

    public void setWriteIntent(Reservation reservation,Map<String,Object> intent)throws IOException {
        lockForAdmission();try{
        requireActive(reservation);reservation.writeIntent.clear();reservation.writeIntent.putAll(intent);persist(reservation);
        }finally{stateLock.unlock();}
    }
    public void clearWriteIntent(Reservation reservation)throws IOException {lockForAdmission();try{requireActive(reservation);reservation.writeIntent.clear();persist(reservation);}finally{stateLock.unlock();}}

    public void grow(Reservation reservation,Map<Path,Long> additional)throws IOException {
        lockForAdmission();try{
        requireActive(reservation);var updated=new LinkedHashMap<String,Long>();var refs=new LinkedHashMap<>(reservation.volumes);
        for(var request:additional.entrySet()){long bytes=request.getValue();if(bytes<0)throw new IllegalArgumentException("Negative storage commitment");var identity=volumes.resolve(request.getKey());refs.put(identity.deviceId(),VolumeRef.from(identity));updated.merge(identity.deviceId(),bytes,StorageAdmission::addExact);}
        var lockIds=sorted(updated.keySet());withLocks(lockIds,()->checkCapacity(refs,updated,reservation));
        reservation.volumes.clear();reservation.volumes.putAll(refs);updated.forEach((volume,bytes)->reservation.commitments.merge(volume,bytes,StorageAdmission::addExact));persist(reservation);
        }finally{stateLock.unlock();}
    }

    /** Materialized bytes are already represented in filesystem free space and are not reserved twice. */
    public void materialized(Reservation reservation,Path volume,long actualBytes)throws IOException {
        lockForAdmission();try{
        requireActive(reservation);if(actualBytes<0)throw new IllegalArgumentException("Negative materialized size");var identity=volumes.resolve(volume);long held=reservation.commitments.getOrDefault(identity.deviceId(),0L);if(actualBytes>held)throw new IOException("Materialized write exceeds its admission commitment");long remain=held-actualBytes;if(remain==0)reservation.commitments.remove(identity.deviceId());else reservation.commitments.put(identity.deviceId(),remain);persist(reservation);
        }finally{stateLock.unlock();}
    }

    public void release(Reservation reservation,Map<Path,Long> unused)throws IOException {
        lockForAdmission();try{
        requireActive(reservation);for(var item:unused.entrySet()){var identity=volumes.resolve(item.getKey());long bytes=item.getValue();long held=reservation.commitments.getOrDefault(identity.deviceId(),0L);if(bytes<0||bytes>held)throw new IOException("Invalid unused storage release");long remain=held-bytes;if(remain==0)reservation.commitments.remove(identity.deviceId());else reservation.commitments.put(identity.deviceId(),remain);}persist(reservation);
        }finally{stateLock.unlock();}
    }

    /** Close only after the owning operation is resolved; materialized artifacts remain owned by their domain. */
    public void close(Reservation reservation)throws IOException {
        lockForAdmission();try{
        if(reservation!=null&&reservation.closed)return;
        requireActive(reservation);Path record=recordPath(reservation.id);Files.deleteIfExists(record);reservations.remove(reservation.id);reservation.closed=true;
        }finally{stateLock.unlock();}
    }

    public void reconcile(OwnerResolver resolver)throws IOException {
        stateLock.lock();try{
        for(Reservation reservation:new ArrayList<>(reservations.values())){
            // Re-resolve every persisted binding before changing or releasing any reservation.
            for(VolumeRef ref:reservation.volumes.values())volumes.reResolve(ref.deviceId,ref.volumeGuid,Path.of(ref.ownerRoot));
            OwnerState state=resolver.resolve(reservation);
            if(state==null||!state.authoritative)continue; // Ambiguous ownership remains protected.
            if(!state.active){Files.deleteIfExists(recordPath(reservation.id));reservations.remove(reservation.id);reservation.closed=true;continue;}
            reservation.commitments.clear();reservation.commitments.putAll(state.remainingCommitments);
            reservation.writeIntent.clear();reservation.writeIntent.putAll(state.writeIntent);
            reservation.serverRunId=serverRunId;persist(reservation);
        }
        }finally{stateLock.unlock();}
    }
    @FunctionalInterface public interface OwnerResolver { OwnerState resolve(Reservation reservation)throws IOException; }
    public record OwnerState(boolean authoritative,boolean active,Map<String,Long> remainingCommitments,Map<String,Object> writeIntent) {
        public OwnerState {remainingCommitments=Map.copyOf(remainingCommitments);writeIntent=Map.copyOf(writeIntent);}
        public static OwnerState uncertain(){return new OwnerState(false,true,Map.of(),Map.of());}
        public static OwnerState absent(){return new OwnerState(true,false,Map.of(),Map.of());}
    }

    private void load()throws IOException {
        try(var entries=Files.list(directory)){
            for(Path file:entries.toList()){
                if(!file.getFileName().toString().endsWith(".json"))continue;
                if(Files.isSymbolicLink(file)||Files.size(file)>MAX_RECORD_BYTES)throw new IOException("Invalid storage reservation record");
                Map<String,Object> saved=Json.read(Files.readString(file));
                if(!saved.keySet().equals(java.util.Set.of("id","ownerKind","ownerId","serverRunId","operationId","purpose","state","volumes","writeIntent"))||!"active".equals(saved.get("state")))throw new IOException("Invalid storage reservation record");
                String id=(String)saved.get("id");var refs=new LinkedHashMap<String,VolumeRef>();var commitments=new LinkedHashMap<String,Long>();
                if(!(saved.get("volumes") instanceof List<?> volumeRows)||!(saved.get("writeIntent") instanceof Map<?,?>)||saved.get("ownerKind") instanceof String ownerKind&&ownerKind.isBlank()||saved.get("ownerId") instanceof String ownerId&&ownerId.isBlank()||saved.get("serverRunId") instanceof String runId&&runId.isBlank()||saved.get("purpose") instanceof String purpose&&purpose.isBlank()||saved.get("operationId")!=null&&!(saved.get("operationId") instanceof String))throw new IOException("Invalid storage reservation record");
                for(Object raw:volumeRows){var row=ValueCodec.map(raw);if(!row.keySet().equals(java.util.Set.of("deviceId","volumeGuid","ownerRoot","commitment")))throw new IOException("Invalid storage volume record");String device=(String)row.get("deviceId");String volumeGuid=(String)row.get("volumeGuid"),ownerRoot=(String)row.get("ownerRoot");if(device==null||volumeGuid==null||ownerRoot==null||refs.containsKey(device))throw new IOException("Invalid storage volume identity");refs.put(device,new VolumeRef(device,volumeGuid,ownerRoot));long bytes=((Number)row.get("commitment")).longValue();if(bytes<0)throw new IOException("Invalid storage commitment");if(bytes>0)commitments.put(device,bytes);}
                if(refs.isEmpty())throw new IOException("Storage reservation has no volume bindings");
                var reservation=new Reservation(id,(String)saved.get("ownerKind"),(String)saved.get("ownerId"),(String)saved.get("operationId"),(String)saved.get("purpose"),(String)saved.get("serverRunId"),refs,commitments,new LinkedHashMap<>(ValueCodec.map(saved.get("writeIntent"))),false);
                if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||!file.getFileName().toString().equals(id+".json")||reservation.ownerKind==null||reservation.ownerId==null||reservation.serverRunId==null||reservation.purpose==null)throw new IOException("Invalid storage reservation ownership");
                reservations.put(id,reservation);
            }
        }
    }

    private void checkCapacity(Map<String,VolumeRef> refs,Map<String,Long> additions,Reservation current)throws IOException {
        for(var addition:additions.entrySet()){
            long requested=addition.getValue(),held=0;
            for(Reservation reservation:reservations.values())if(reservation!=current)held=addExact(held,reservation.commitments.getOrDefault(addition.getKey(),0L));
            long required=addExact(held,requested);if(current!=null)required=addExact(required,current.commitments.getOrDefault(addition.getKey(),0L));VolumeRef ref=refs.get(addition.getKey());volumes.reResolve(ref.deviceId,ref.volumeGuid,Path.of(ref.ownerRoot));long free=Files.getFileStore(Path.of(ref.ownerRoot)).getUsableSpace();
            if(required>free)throw new StoragePressureException("Insufficient available storage for the requested operation");
        }
    }
    private static long addExact(long left,long right){try{return Math.addExact(left,right);}catch(ArithmeticException e){throw new StoragePressureException("Storage commitment is not representable");}}
    private static List<String> sorted(java.util.Collection<String> ids){return ids.stream().sorted().toList();}
    private void withLocks(List<String> ids,IoAction action)throws IOException {var locks=ids.stream().map(id->volumeLocks.computeIfAbsent(id,k->new ReentrantLock())).toList();var acquired=new ArrayList<ReentrantLock>();try{for(ReentrantLock lock:locks){if(!lock.tryLock())throw new StoragePressureException("Storage admission is busy");acquired.add(lock);}action.run();}finally{for(int i=acquired.size()-1;i>=0;i--)acquired.get(i).unlock();}}
    private void lockForAdmission()throws IOException {if(!stateLock.tryLock())throw new StoragePressureException("Storage admission is busy");}
    @FunctionalInterface private interface IoAction {void run()throws IOException;}
    private void requireActive(Reservation reservation)throws IOException {if(reservation==null||reservation.closed||reservations.get(reservation.id)!=reservation)throw new IOException("Storage reservation is not active");}

    private void persist(Reservation reservation)throws IOException {
        var rows=new ArrayList<Map<String,Object>>();for(var ref:reservation.volumes.values())rows.add(Json.map("deviceId",ref.deviceId,"volumeGuid",ref.volumeGuid,"ownerRoot",ref.ownerRoot,"commitment",reservation.commitments.getOrDefault(ref.deviceId,0L)));
        var record=Json.map("id",reservation.id,"ownerKind",reservation.ownerKind,"ownerId",reservation.ownerId,"serverRunId",reservation.serverRunId,"operationId",reservation.operationId,"purpose",reservation.purpose,"state","active","volumes",rows,"writeIntent",reservation.writeIntent);
        byte[] bytes=Json.write(record).getBytes(java.nio.charset.StandardCharsets.UTF_8);if(bytes.length>MAX_RECORD_BYTES)throw new IOException("Storage reservation metadata exceeds its bounded record size");Path target=recordPath(reservation.id),temporary=directory.resolve(reservation.id+".tmp");
        try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){channel.write(java.nio.ByteBuffer.wrap(bytes));channel.force(true);}
        try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(java.nio.file.AtomicMoveNotSupportedException failure){Files.deleteIfExists(temporary);throw new IOException("Atomic reservation update is unavailable",failure);}
        try(FileChannel channel=FileChannel.open(directory,StandardOpenOption.READ)){channel.force(true);}catch(IOException ignored){}
    }
    private Path recordPath(String id)throws IOException {if(id==null||!id.matches("[0-9a-f-]{36}"))throw new IOException("Invalid storage reservation identity");Path path=directory.resolve(id+".json").normalize();if(!path.startsWith(directory))throw new IOException("Invalid storage reservation identity");return path;}

    @Override public void close(){stateLock.lock();try{for(Reservation reservation:reservations.values())reservation.closed=true;reservations.clear();}finally{stateLock.unlock();}}
    public static final class Reservation {
        public final String id,ownerKind,ownerId,operationId,purpose;private String serverRunId;private final Map<String,VolumeRef> volumes;private final Map<String,Long> commitments;private final Map<String,Object> writeIntent;private boolean closed;
        private Reservation(String id,String ownerKind,String ownerId,String operationId,String purpose,String serverRunId,Map<String,VolumeRef> volumes,Map<String,Long> commitments,Map<String,Object> intent,boolean closed){this.id=id;this.ownerKind=ownerKind;this.ownerId=ownerId;this.operationId=operationId;this.purpose=purpose;this.serverRunId=serverRunId;this.volumes=volumes;this.commitments=commitments;this.writeIntent=intent;this.closed=closed;}
        public Map<String,Long> commitments(){return Map.copyOf(commitments);}
        public Map<String,Object> writeIntent(){return Map.copyOf(writeIntent);}
        public String serverRunId(){return serverRunId;}
    }
    private record VolumeRef(String deviceId,String volumeGuid,String ownerRoot){static VolumeRef from(StorageVolumeIdentity.Identity identity){return new VolumeRef(identity.deviceId(),identity.volumeGuid(),identity.ownerRoot());}}
    public static final class StoragePressureException extends RuntimeException {public StoragePressureException(String message){super(message);}}
}
