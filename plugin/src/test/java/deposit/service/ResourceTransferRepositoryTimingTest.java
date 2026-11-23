package deposit.service;
import static org.junit.jupiter.api.Assertions.*;
import deposit.common.*;
import deposit.common.Results.DataRow;
import deposit.repository.*;
import java.io.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.*;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
/** Bounded real-byte timing through the same public ProcedureService path used by import upload. */
class ResourceTransferRepositoryTimingTest {
    private static final int CHUNKS=1,CHUNK=ResourceFiles.MAX_CHUNK;
    @TempDir Path temporary;
    private DatabaseManagementService management;
    private GraphDatabaseAPI database;
    private ServerContext server;
    private String previousConfig,actor,token;
    private final Map<String,Long> phases=new LinkedHashMap<>();
    private long testStarted;

    @BeforeEach void setUp()throws Exception {
        previousConfig=System.getProperty("deposit.config");
        Path root=Files.createDirectories(temporary.resolve("resources")),exports=Files.createDirectories(temporary.resolve("exports")),config=temporary.resolve("deposit-test.properties");
        var properties=new Properties();properties.setProperty("resourceRoot",root.toString());properties.setProperty("exportRoot",exports.toString());
        try(var out=Files.newOutputStream(config)){properties.store(out,"timing");}System.setProperty("deposit.config",config.toString());
        management=new DatabaseManagementServiceBuilderImplementation(temporary.resolve("database"),type->type!=DepositExtension.class).build();
        database=(GraphDatabaseAPI)management.database("neo4j");server=ServerContext.attach(database.databaseId().databaseId().uuid());
        var field=ServerContext.class.getDeclaredField("database");field.setAccessible(true);field.set(server,database);server.storage=new StorageManager(server,database);SchemaRepository.initialize(database);
        var ready=ServerContext.class.getDeclaredMethod("markReadyForTesting");ready.setAccessible(true);ready.invoke(server);
        database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).addMonitorListener(server);management.registerTransactionEventListener("neo4j",server);
        actor=UUID.randomUUID().toString();token=ServerContext.randomToken();
        try(var tx=database.beginTx()){
            var repo=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));repo.createRestored("User",null,actor,Json.map("enabled",true,"serverRole","user"),actor);
            Node ns=repo.create("Namespace",null,actor,Json.map("name","Transfer timing receiver","description","","sequence",0L,"historyMode","disabled"));String namespace=CoreRepository.id(ns);repo.setRole(actor,namespace,"owner");
            var session=new ServerContext.Session(actor,((InternalTransaction)tx).securityContext().subject().executingUser(),server.sessionTime());session.namespace=namespace;session.baselines.put(namespace,0L);server.registerSession(Json.hash(token),session);tx.commit();
        }
    }
    @AfterEach void tearDown(){
        try{if(database!=null&&server!=null){management.unregisterTransactionEventListener("neo4j",server);database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).removeMonitorListener(server);}}
        finally{try{if(server!=null&&server.storage!=null)server.storage.close();}finally{try{if(management!=null)management.shutdown();}finally{if(database!=null)ServerContext.detach(database.databaseId().databaseId().uuid());if(previousConfig==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previousConfig);}}}
    }
    @Test void fourMiBPublicUploadWithFreshReceiptReplayAndReadbackTimings()throws Exception {
        testStarted=System.nanoTime();
        assertEquals(4L*1024*1024,CHUNK,"representative timing fixture must remain exactly one 4 MiB public chunk");
        Path source=temporary.resolve("timing.bin");var chunks=new ArrayList<byte[]>();
        try(var out=new BufferedOutputStream(Files.newOutputStream(source))){
            for(int i=0;i<CHUNKS;i++){byte[] bytes=new byte[CHUNK];Arrays.fill(bytes,(byte)(i+1));chunks.add(bytes);out.write(bytes);}
        }
        long total=Files.size(source),readThen=System.nanoTime();String checksum=ResourceFiles.checksum(source);phase("fixture-four-MiB-read-checksum",readThen);progress("source-ready",0,total);
var observer=ServerContext.class.getDeclaredMethod("storageObserverForTesting",java.util.function.Consumer.class);observer.setAccessible(true);
        var observerToken=new AtomicReference<Long>();
        try{
            observer.invoke(server,(java.util.function.Consumer<String>)point->{long now=System.nanoTime();if(point.equals("chunk-flushed")){Long start=observerToken.get();if(start!=null)phase("fresh-chunk-to-flushed",start);}phase("storage-point-"+point+"-observed",now);});
            
            long then=System.nanoTime();Map<String,Object> begin=call("deposit.resource.beginUpload",UUID.randomUUID(),args("filename","timing.zip","bytes",total,"sha256",checksum,"mediaType","application/zip"));phase("public-beginUpload",then);
            String upload=(String)begin.get("uploadId");assertEquals(ResourceFiles.CHUNK,((Number)begin.get("chunkBytes")).intValue());assertEquals(1,count("DepositTransfer"));
            long offset=0;
            for(int i=0;i<CHUNKS;i++){
                byte[] bytes=chunks.get(i);String body=Base64.getEncoder().encodeToString(bytes),digest=ResourceFiles.checksum(bytes);UUID operation=UUID.randomUUID();observerToken.set(null);
                long callStart=System.nanoTime();observerToken.set(callStart);then=callStart;Map<String,Object> fresh=call("deposit.resource.uploadChunk",operation,args("uploadId",upload,"offset",offset,"bytesBase64",body,"chunkSha256",digest));phase("public-fresh-chunk-"+i,then);
                assertEquals(offset+bytes.length,((Number)fresh.get("nextOffset")).longValue());offset+=bytes.length;progress("upload-acknowledged",offset,total);
                then=System.nanoTime();Map<String,Object> replay=call("deposit.resource.uploadChunk",operation,args("uploadId",upload,"offset",offset-bytes.length,"bytesBase64",body,"chunkSha256",digest));phase("public-receipt-replay-chunk-"+i,then);
                assertEquals(fresh,replay,"same-operation committed receipt replay");measureCommittedLookup(upload);
            }
            assertEquals(total,offset);assertEquals(CHUNKS,count("DepositTransferChunk"));
            long stagedBytes=readStagedBytes();
            then=System.nanoTime();Map<String,Object> finish=call("deposit.resource.finishUpload",UUID.randomUUID(),args("uploadId",upload));phase("public-finishUpload",then);String resource=(String)finish.get("resourceId");progress("resource-published",total,total);
            then=System.nanoTime();measureReadback(checksum,resource);phase("content-publish-and-streaming-readback",then);progress("readback-verified",total,total);
            assertEquals(1,count("DResource"));assertTrue(server.storage.content.exists(resource));
            long wall=phases.entrySet().stream().filter(e->e.getKey().startsWith("public-")).mapToLong(Map.Entry::getValue).sum();
            var report=new LinkedHashMap<String,Object>();report.put("fixture","ResourceTransferRepositoryTimingTest");report.put("chunks",CHUNKS);report.put("chunkBytes",CHUNK);report.put("payloadBytes",total);report.put("checksum",checksum);report.put("publicWallMicros",wall/1_000L);report.put("micros",phases);report.put("fixtureCardinality",chunks.size());
            Path output=Path.of(System.getProperty("deposit.test.root",temporary.toString()),"ResourceTransferRepositoryTimingTest-results.json");Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output,Json.write(report)+System.lineSeparator(),StandardCharsets.UTF_8);
            System.out.println("TIMING payloadBytes="+total+" publicWallMs="+String.format(Locale.ROOT,"%.3f",wall/1_000_000.0)+" stagedBytes="+stagedBytes+" results="+output);
            for(var entry:phases.entrySet())System.out.println("TIMING "+entry.getKey()+" ms="+String.format(Locale.ROOT,"%.3f",entry.getValue()/1_000_000.0));
        }finally{observer.invoke(server,(java.util.function.Consumer<String>)point->{});}
    }
    private Map<String,Object> call(String procedure,UUID operation,Map<String,Object> request)throws Exception {
        try(var tx=database.beginTx()){
            var envelope=new LinkedHashMap<String,Object>();envelope.put("token",token);if(operation!=null)envelope.put("operationId",operation.toString());
            var context=new RequestContext(tx,envelope);context.publicCall=true;context.procedure=procedure;context.request=request;
            var actions=new SessionActionService(context);actions.begin(procedure,ProcedureCatalog.spec(procedure).bindings().stream().map(request::get).collect(java.util.stream.Collectors.toList()));
var row=(DataRow)new ProcedureService(context).execute().findFirst().orElseThrow();actions.observe();tx.commit();return row.data;
        }
    }
    private long count(String label){long then=System.nanoTime();try(var tx=database.beginTx()){long count;try(var nodes=tx.findNodes(Label.label(label))){count=nodes.stream().count();}tx.rollback();phase("db-count-"+label,then);return count;}}
    private void measureCommittedLookup(String upload){long then=System.nanoTime();try(var tx=database.beginTx()){Node transfer=tx.findNode(Label.label("DepositTransfer"),"id",upload);assertNotNull(transfer);assertTrue(transfer.hasProperty("nextOffset"));assertEquals(0L,((Number)transfer.getProperty("nextOffset")).longValue()%CHUNK);tx.rollback();phase("db-committed-transfer-lookup-and-nextOffset",then);}}
    private long readStagedBytes()throws Exception {long then=System.nanoTime();long bytes;try(var files=Files.walk(server.storage.files.root.resolve("staging"))){bytes=files.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(IOException e){throw new UncheckedIOException(e);}}).sum();}phase("staged-byte-readback",then);assertEquals((long)CHUNKS*CHUNK,bytes);return bytes;}
    private void measureReadback(String expected,String resource)throws Exception {String name;long bytes;try(var tx=database.beginTx()){Node node=tx.findNode(Label.label("DResource"),"id",resource);assertNotNull(node);name=(String)node.getProperty("storedFilename");bytes=((Number)node.getProperty("bytes")).longValue();tx.rollback();}var content=new ResourceStorage.Content(resource,name,bytes,expected);MessageDigest digest=MessageDigest.getInstance("SHA-256");long actualBytes=0;byte[] buffer=new byte[64*1024];try(var in=server.storage.content.open(content,0,bytes)){int n;while((n=in.read(buffer))!=-1){digest.update(buffer,0,n);actualBytes=Math.addExact(actualBytes,n);}}assertEquals(bytes,actualBytes);assertEquals(expected,HexFormat.of().formatHex(digest.digest()));}
    private void phase(String name,long start){phases.merge(name,System.nanoTime()-start,Math::addExact);}
    private void progress(String milestone,long completed,long total)throws IOException {
        String configured=System.getProperty("deposit.test.progress");
        if(configured==null||configured.isBlank())throw new IllegalStateException("deposit.test.progress is required for supervised timing");
        Path output=Path.of(configured);Files.createDirectories(output.toAbsolutePath().getParent());
        var event=new LinkedHashMap<String,Object>();event.put("milestone",milestone);event.put("completedBytes",completed);event.put("totalBytes",total);event.put("elapsedMicros",(System.nanoTime()-testStarted)/1_000L);
        Files.writeString(output,Json.write(event)+System.lineSeparator(),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private static Map<String,Object> args(Object... values){var map=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)map.put((String)values[i],values[i+1]);return map;}
}
