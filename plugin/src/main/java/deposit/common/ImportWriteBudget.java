package deposit.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Entity;

/**
 * Transaction-owned future-space estimate for import graph, result and receipt writes.
 * It reserves in blocks before work, accounts logical property bytes and record count,
 * and leaves filesystem free-space checks authoritative for physical allocation.
 */
public final class ImportWriteBudget {
    private static final long RESERVATION_BLOCK=1024L*1024;
    // Neo4j record/index allowance is deliberately separated from serialized property
    // bytes. It is a planning estimate for this pinned distribution, not a disk upper bound;
    // filesystem free-space admission remains authoritative for physical allocation.
    private static final long NODE_AND_INDEX_ESTIMATE=512;
    private static final long RELATIONSHIP_AND_INDEX_ESTIMATE=256;
    private static final long NODE_AND_LOG_COMMAND_ESTIMATE=128;
    private static final long RELATIONSHIP_AND_LOG_COMMAND_ESTIMATE=128;
    private static final long LOG_IMAGE_COPIES=2;
    private static final long TRANSACTION_ALLOWANCE=64L*1024;

    private final RequestContext context;
    private final StorageAdmission.Reservation reservation;
    private final Path databasePath,transactionLogPath;
    private long estimatedDatabaseBytes,reservedDatabaseBytes,estimatedLogBytes,reservedLogBytes;
    private boolean released,retainForCleanup;

    private ImportWriteBudget(RequestContext context,StorageAdmission.Reservation reservation,
                              Path databasePath,Path transactionLogPath) {
        this.context=context;this.reservation=reservation;this.databasePath=databasePath;
        this.transactionLogPath=transactionLogPath;
    }

    public static ImportWriteBudget forRequest(RequestContext context) {
        String scope=context.namespaceId==null?"server":context.namespaceId;
        return forRequest(context,scope,context.namespaceId);
    }

    /** Reserve this action under its receipt scope while retaining its destination Namespace separately. */
    public static ImportWriteBudget forRequest(RequestContext context,String receiptScope,String resultNamespaceId) {
        ImportWriteBudget current=context.state.importWriteBudget;
        if(current!=null)return current;
        if(receiptScope==null||receiptScope.isBlank())throw new IllegalArgumentException("Import receipt scope is required");
        // Lightweight ServerContext fixtures used by isolated repository tests do not
        // initialize resource admission; a live Server cannot accept requests before this exists.
        if(context.server.storage==null||context.server.database()==null){
            ImportWriteBudget fixture=new ImportWriteBudget(context,null,null,null);
            context.state.importWriteBudget=fixture;context.state.release.add(fixture::releaseAfterTransaction);return fixture;
        }
        Path database=context.server.database().databaseLayout().databaseDirectory();
        Path logs=context.server.database().databaseLayout().getTransactionLogsDirectory();
        Map<Path,Long> initial=paths(0,0,database,logs);
        StorageAdmission.Reservation reservation;
        try {
            reservation=context.server.storage.admission.acquire("import-write",reservationOwnerId(receiptScope,resultNamespaceId,context.actorId,context.operationId),
                context.operationId,"import-database-and-result",initial,
                Json.map("phase","import-write","receiptScope",receiptScope,
                    "operationId",context.operationId,"actorId",context.actorId,"namespaceId",resultNamespaceId,
                    "resultNamespaceId",resultNamespaceId));
        } catch(StorageAdmission.StoragePressureException pressure) {
            context.fail("LIMIT_EXCEEDED",pressure.getMessage());return null;
        } catch(IOException failure) {
            context.fail("LIMIT_EXCEEDED","Unable to reserve import transaction storage");return null;
        }
        ImportWriteBudget created=new ImportWriteBudget(context,reservation,database,logs);
        context.state.importWriteBudget=created;
        context.state.release.add(created::releaseAfterTransaction);
        created.reserve(TRANSACTION_ALLOWANCE,TRANSACTION_ALLOWANCE);
        return created;
    }

    /** Reserve the expected graph write before materialization starts. */
    public void reserveGraph(Map<String,Object> graph) {
        reserveGraph(graph,null);
    }
    public void reserveGraph(Map<String,Object> graph,Set<String> currentResourceIds) {
        for(var category:graph.entrySet())if(category.getValue() instanceof List<?> rows){
            for(Object row:rows){
                if(category.getKey().equals("resources")&&currentResourceIds!=null
                    &&row instanceof Map<?,?> resource&&!currentResourceIds.contains(resource.get("id")))continue;
                long bytes=serializedBytes(row);
                int nodes=plannedNodes(category.getKey(),row);
                int relationships=plannedRelationships(category.getKey(),row);
                long database=checkedAdd(bytes,checkedAdd(Math.multiplyExact((long)nodes,NODE_AND_INDEX_ESTIMATE),
                    Math.multiplyExact((long)relationships,RELATIONSHIP_AND_INDEX_ESTIMATE)));
                long log=checkedAdd(Math.multiplyExact(bytes,LOG_IMAGE_COPIES),checkedAdd(Math.multiplyExact((long)nodes,NODE_AND_LOG_COMMAND_ESTIMATE),
                    Math.multiplyExact((long)relationships,RELATIONSHIP_AND_LOG_COMMAND_ESTIMATE)));
                reserve(database,log);
            }
        }
    }

    /** Reserve one exact history record and its ownership/retention edges before writing it. */
    public void reserveHistoryRecord(Map<String,Object> properties,int relationships) {
        long[] estimate=historyRecordEstimate(properties,relationships);reserve(estimate[0],estimate[1]);
    }
    static long[] historyRecordEstimate(Map<String,Object> properties,int relationships) {
        var encoded=new LinkedHashMap<String,Object>();properties.forEach((key,value)->encoded.put(key,portable(value)));long payload=serializedBytes(encoded);
        return new long[]{checkedAdd(payload,checkedAdd(NODE_AND_INDEX_ESTIMATE,Math.multiplyExact((long)relationships,RELATIONSHIP_AND_INDEX_ESTIMATE))),
            checkedAdd(Math.multiplyExact(payload,LOG_IMAGE_COPIES),checkedAdd(NODE_AND_LOG_COMMAND_ESTIMATE,Math.multiplyExact((long)relationships,RELATIONSHIP_AND_LOG_COMMAND_ESTIMATE)))};
    }

    /** Reserve one result/store record before its Neo4j node or property is written. */
    public void reserveRecord(Map<String,Object> properties) {
        long payload=serializedBytes(properties);reserve(payload+NODE_AND_INDEX_ESTIMATE,Math.multiplyExact(payload,LOG_IMAGE_COPIES)+NODE_AND_LOG_COMMAND_ESTIMATE);
    }

    /** Reserve a pre-existing node or relationship before a form-scope deletion mutates it. */
    public void reserveBeforeMutation(Entity entity) {
        var properties=new LinkedHashMap<String,Object>();entity.getAllProperties().forEach((key,value)->properties.put(key,portable(value)));
        long payload=serializedBytes(properties);long databaseOverhead=entity instanceof Node?NODE_AND_INDEX_ESTIMATE:RELATIONSHIP_AND_INDEX_ESTIMATE;
        long logOverhead=entity instanceof Node?NODE_AND_LOG_COMMAND_ESTIMATE:RELATIONSHIP_AND_LOG_COMMAND_ESTIMATE;
        reserve(payload+databaseOverhead,Math.multiplyExact(payload,LOG_IMAGE_COPIES)+logOverhead);
    }

    /** Reserve SQLite scratch growth before each bounded index mutation. */
    public ScratchLease scratchLease() { return new ScratchLease(); }
    public void ownHistoryWorkspace(Path directory){
        if(reservation==null)return;var intent=new LinkedHashMap<>(reservation.writeIntent());intent.put("historyScratchPath",context.server.storage.files.root.relativize(directory).toString().replace('\\','/'));
        try{context.server.storage.admission.setWriteIntent(reservation,intent);}catch(IOException failure){throw new UncheckedIOException(failure);}
    }
    /** Failed scratch deletion remains owned until startup reclaims its marked private directory. */
    public void retainForScratchCleanup(){
        retainForCleanup=true;if(reservation==null)return;var intent=new LinkedHashMap<>(reservation.writeIntent());intent.put("scratchCleanupPending",true);
        try{context.server.storage.admission.setWriteIntent(reservation,intent);}catch(IOException failure){throw new UncheckedIOException(failure);}
    }

    public final class ScratchLease implements AutoCloseable {
        private long estimated,reserved;
        private boolean closed;
        public void reserve(long bytes) {
        if(bytes<0)context.fail("LIMIT_EXCEEDED","Import scratch estimate is not representable");
        if(closed)throw new IllegalStateException("Import scratch lease is closed");
        long next=checkedAdd(estimated,bytes);
        if(reservation==null){estimated=next;return;}
        long addition=Math.max(0,next-reserved);
        if(addition>0){
            addition=Math.max(RESERVATION_BLOCK,addition);
            try{context.server.storage.admission.grow(reservation,Map.of(context.server.storage.files.root,addition));}
            catch(StorageAdmission.StoragePressureException pressure){context.fail("LIMIT_EXCEEDED",pressure.getMessage());return;}
            catch(IOException failure){context.fail("LIMIT_EXCEEDED","Unable to reserve import scratch storage");return;}
            reserved=checkedAdd(reserved,addition);
        }
        estimated=next;
        }
        @Override public void close(){if(closed)return;closed=true;if(reservation==null||reserved==0)return;try{context.server.storage.admission.release(reservation,Map.of(context.server.storage.files.root,reserved));}catch(IOException failure){context.server.storage.diagnostic="Import scratch admission cleanup pending";}}
    }

    /** Keep a bounded allowance for the receipt and transaction completion record. */
    public void reserveReceiptAllowance(){reserve(4L*1024+NODE_AND_INDEX_ESTIMATE,4L*1024*LOG_IMAGE_COPIES+NODE_AND_LOG_COMMAND_ESTIMATE);}

    /** Release credit that no write consumed; consumed estimates remain through transaction end. */
    public void finish() {
        if(reservation==null||retainForCleanup)return;
        long unusedDatabase=reservedDatabaseBytes-estimatedDatabaseBytes,unusedLog=reservedLogBytes-estimatedLogBytes;
        if(unusedDatabase<=0&&unusedLog<=0)return;
        try {context.server.storage.admission.release(reservation,paths(Math.max(0,unusedDatabase),Math.max(0,unusedLog),databasePath,transactionLogPath));reservedDatabaseBytes-=Math.max(0,unusedDatabase);reservedLogBytes-=Math.max(0,unusedLog);}
        catch(IOException failure){context.fail("LIMIT_EXCEEDED","Unable to settle import transaction storage reservation");}
    }

    private void reserve(long databaseBytes,long logBytes) {
        long nextDatabase=checkedAdd(estimatedDatabaseBytes,databaseBytes),nextLog=checkedAdd(estimatedLogBytes,logBytes);
        if(reservation==null){estimatedDatabaseBytes=nextDatabase;estimatedLogBytes=nextLog;return;}
        long addDatabase=nextDatabase>reservedDatabaseBytes?Math.max(RESERVATION_BLOCK,nextDatabase-reservedDatabaseBytes):0;
        long addLog=nextLog>reservedLogBytes?Math.max(RESERVATION_BLOCK,nextLog-reservedLogBytes):0;
        if(addDatabase>0||addLog>0){
            try {context.server.storage.admission.grow(reservation,paths(addDatabase,addLog,databasePath,transactionLogPath));}
            catch(StorageAdmission.StoragePressureException pressure){context.fail("LIMIT_EXCEEDED",pressure.getMessage());return;}
            catch(IOException failure){context.fail("LIMIT_EXCEEDED","Unable to reserve import transaction storage");return;}
            reservedDatabaseBytes=checkedAdd(reservedDatabaseBytes,addDatabase);reservedLogBytes=checkedAdd(reservedLogBytes,addLog);
        }
        estimatedDatabaseBytes=nextDatabase;estimatedLogBytes=nextLog;
    }

    private static long serializedBytes(Object value) {
        return Json.write(value).getBytes(StandardCharsets.UTF_8).length;
    }
    private static Object portable(Object value){if(value instanceof java.time.temporal.TemporalAccessor)return value.toString();if(value instanceof double[] array)return java.util.Arrays.stream(array).boxed().toList();if(value instanceof long[] array)return java.util.Arrays.stream(array).boxed().toList();return value;}
    private static int plannedNodes(String category,Object raw) {
        Map<?,?> row=raw instanceof Map<?,?> map?map:Map.of();
        return switch(category) {
            case "resources" -> 2; // DResource and DepositResourceLocation
            case "classes" -> 1+listSize(row.get("descriptors"));
            case "descriptors" -> row.get("geometryId") instanceof String?3:1;
            default -> 1;
        };
    }
    private static int plannedRelationships(String category,Object raw) {
        Map<?,?> row=raw instanceof Map<?,?> map?map:Map.of();
        return switch(category) {
            case "classes" -> 1+listSize(row.get("descriptors"));
            case "objects","memberships","descriptors","relationships","classRelationships","patterns","userTools" -> 1;
            case "resources" -> 0;
            default -> 0;
        };
    }
    private static int listSize(Object value) { return value instanceof List<?> rows?rows.size():0; }
    public static String reservationOwnerId(String namespaceId,String actorId,String operationId) {
        try {
            byte[] identity=(namespaceId+"\u0000"+actorId+"\u0000"+operationId).getBytes(StandardCharsets.UTF_8);
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(identity);
            return "import-"+java.util.HexFormat.of().formatHex(digest);
        } catch(NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 is unavailable",unavailable); }
    }

    public static String reservationOwnerId(String receiptScope,String resultNamespaceId,String actorId,String operationId) {
        if(java.util.Objects.equals(receiptScope,resultNamespaceId))return reservationOwnerId(receiptScope,actorId,operationId);
        try {
            byte[] identity=(receiptScope+"\u0000"+resultNamespaceId+"\u0000"+actorId+"\u0000"+operationId).getBytes(StandardCharsets.UTF_8);
            return "import-"+java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity));
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static long checkedAdd(long left,long right) {
        try{return Math.addExact(left,right);}catch(ArithmeticException overflow){throw ExceptionHandler.fail("LIMIT_EXCEEDED","Import storage estimate is not representable");}
    }
    private static Map<Path,Long> paths(long databaseBytes,long logBytes,Path database,Path logs) {
        var result=new LinkedHashMap<Path,Long>();result.merge(database,databaseBytes,ImportWriteBudget::checkedAdd);
        result.merge(logs,logBytes,ImportWriteBudget::checkedAdd);return result;
    }

    private void releaseAfterTransaction() {
        if(released)return;released=true;
        if(context.state.importWriteBudget==this)context.state.importWriteBudget=null;
        if(reservation==null||retainForCleanup)return;
        try{context.server.storage.admission.close(reservation);}
        catch(IOException failure){context.server.storage.diagnostic="Import database/result admission cleanup pending";}
    }
}
