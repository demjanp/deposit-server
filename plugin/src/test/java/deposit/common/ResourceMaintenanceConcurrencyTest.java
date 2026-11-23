package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import deposit.repository.*;
import deposit.service.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.*;
import org.neo4j.graphdb.Node;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.kernel.api.net.NetworkConnectionTracker;

/** Small real-path control for storage maintenance running alongside the public uploader. */
class ResourceMaintenanceConcurrencyTest {
    private static final int BACKGROUND_LOCATIONS=32, PAYLOAD=1024*1024;
    @TempDir Path temporary;
    private DatabaseManagementService management;
    private GraphDatabaseAPI database;
    private ServerContext server;
    private String previousConfig,actor,token,namespace;
    private OperationAttemptTest.Connection execution;
    private ExecutorService uploader;

    private void initialize()throws Exception {
        previousConfig=System.getProperty("deposit.config");
        Path root=Files.createDirectories(temporary.resolve("resources")),exports=Files.createDirectories(temporary.resolve("exports")),config=temporary.resolve("deposit-test.properties");
        var properties=new Properties();properties.setProperty("resourceRoot",root.toString());properties.setProperty("exportRoot",exports.toString());
        try(var out=Files.newOutputStream(config)){properties.store(out,"maintenance-timing");}System.setProperty("deposit.config",config.toString());
        management=new DatabaseManagementServiceBuilderImplementation(temporary.resolve("database"),type->type!=DepositExtension.class).build();
        database=(GraphDatabaseAPI)management.database("neo4j");server=ServerContext.attach(database.databaseId().databaseId().uuid());
        var field=ServerContext.class.getDeclaredField("database");field.setAccessible(true);field.set(server,database);server.storage=new StorageManager(server,database);SchemaRepository.initialize(database);
        var ready=ServerContext.class.getDeclaredMethod("markReadyForTesting");ready.setAccessible(true);ready.invoke(server);
        database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).addMonitorListener(server);management.registerTransactionEventListener("neo4j",server);
        var connections=database.getDependencyResolver().resolveDependency(NetworkConnectionTracker.class);
        execution=new OperationAttemptTest.Connection(connections.newConnectionId("bolt"));
        connections.add(execution);
        actor=UUID.randomUUID().toString();token=ServerContext.randomToken();
        try(var tx=database.beginTx()){
            var repo=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));repo.createRestored("User",null,actor,Json.map("enabled",true,"serverRole","user"),actor);
            Node ns=repo.create("Namespace",null,actor,Json.map("name","Maintenance timing receiver","description","","sequence",0L,"historyMode","disabled"));namespace=CoreRepository.id(ns);repo.setRole(actor,namespace,"owner");
            var session=new ServerContext.Session(actor,((InternalTransaction)tx).securityContext().subject().executingUser(),server.sessionTime());session.namespace=namespace;session.baselines.put(namespace,0L);server.registerSession(Json.hash(token),session);
            for(int i=0;i<BACKGROUND_LOCATIONS;i++)createExpiredLocation(repository(tx),UUID.randomUUID().toString(),i);
            tx.commit();
        }
    }
    private void closeFixture(){
        if(uploader!=null){uploader.shutdownNow();try{if(!uploader.awaitTermination(15,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("uploader pending");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("interrupted",e);}uploader=null;}
        if(server!=null)server.shutdown();
        try{if(database!=null&&server!=null){management.unregisterTransactionEventListener("neo4j",server);database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).removeMonitorListener(server);}}
        finally{try{if(server!=null)server.shutdown();}finally{try{if(management!=null)management.shutdown();}finally{if(database!=null)ServerContext.detach(database.databaseId().databaseId().uuid());if(previousConfig==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previousConfig);}}}}
    @AfterEach void tearDown(){if(uploader!=null)uploader.shutdownNow();}

    @Test void scheduledMaintenanceOneMiBUpload()throws Exception {
        runUpload(true);
    }
    @Test void inactiveMaintenanceOneMiBControl()throws Exception {
        runUpload(false);
    }
    @Test void nonexistentPruneCreatesNoPersistentReservation()throws Exception {
        initialize();
        try {
            Path directory=server.storage.files.root.resolve("admission");
            long before;
            try(var files=Files.list(directory)){before=files.filter(Files::isRegularFile).count();}
            new StorageMaintenanceJobs(server,database,server.storage.files,()->server.storage.content,value->{}).collect(UUID.randomUUID().toString());
            long after;
            try(var files=Files.list(directory)){after=files.filter(Files::isRegularFile).count();}
            assertEquals(before,after,"nonexistent item must not leave collection admission");
            assertEquals(0,after,"missing prune has no other reservation");
        }finally{closeFixture();}
    }
    private void runUpload(boolean active)throws Exception {
        java.lang.reflect.Method observer=null;
        initialize();
        uploader=Executors.newSingleThreadExecutor();
        try {
            if(active)server.storage.start();
            var overlap=new CountDownLatch(1);
            var observed=new AtomicReference<String>();
            observer=ServerContext.class.getDeclaredMethod("storageObserverForTesting",java.util.function.Consumer.class);observer.setAccessible(true);
            if(active)observer.invoke(server,(java.util.function.Consumer<String>)point->{if(point.equals("staging-cleanup-before")){observed.set(point);overlap.countDown();}});
            byte[] bytes=new byte[PAYLOAD];new Random(123456789L).nextBytes(bytes);
            String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            var phases=new LinkedHashMap<String,Long>();
            long wall=System.nanoTime();
            Map<String,Object> begin=timed(phases,"beginUpload",depositCall("deposit.resource.beginUpload",args("filename","maintenance.zip","bytes",(long)PAYLOAD,"sha256",checksum,"mediaType","application/zip")));
            String upload=(String)begin.get("uploadId");
            timed(phases,"uploadChunk",depositCall("deposit.resource.uploadChunk",args("uploadId",upload,"offset",0L,"bytesBase64",Base64.getEncoder().encodeToString(bytes))));
            Map<String,Object> finish=timed(phases,"finishUpload",depositCall("deposit.resource.finishUpload",args("uploadId",upload)));
            long wallNanos=System.nanoTime()-wall;
            String resource=(String)finish.get("resourceId");
            verifyContent(resource,checksum);
            var result=Json.map("fixture",getClass().getSimpleName(),"case",active?"active":"inactive","backgroundLocations",BACKGROUND_LOCATIONS,"payloadBytes",PAYLOAD,"sha256",checksum,"wallMicros",wallNanos/1_000,"micros",phases);
            if(active){
                assertTrue(Files.exists(server.storage.files.root.resolve("staging/background-0.part")),
                    "fixture must retain background work until the observed tick");
                assertTrue(overlap.await(120,TimeUnit.SECONDS),
                    "scheduled maintenance did not reach the published upload");
            }
            if(active)assertEquals("staging-cleanup-before",observed.get(),"published staging was not reclaimed by maintenance");
            Path report=Path.of(System.getProperty("deposit.test.root",temporary.toString()),getClass().getSimpleName()+"-"+(active?"active":"inactive")+"-results.json");
            Files.createDirectories(report.toAbsolutePath().getParent());Files.writeString(report,Json.write(result)+System.lineSeparator(),StandardCharsets.UTF_8);
            System.out.println("MAINTENANCE "+Json.write(result));
        }finally{if(observer!=null)observer.invoke(server,(java.util.function.Consumer<String>)point->{});closeFixture();}
    }
    private void createExpiredLocation(ResourceRepository repo,String id,int index) {
        Node loc=repo.record("DepositResourceLocation",id,Json.map("namespaceId",namespace,"uploadId",id,"storedFilename","background-"+index+".zip","bytes",0L,"checksum",ResourceFiles.checksum(new byte[0]),"leaseUntil",0L,"published",false));
        loc.setProperty("stagingPath","staging/background-"+index+".part");
        try{Files.createDirectories(server.storage.files.path("staging/background-"+index+".part").getParent());Files.write(server.storage.files.path("staging/background-"+index+".part"),new byte[0]);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        repo.record("DepositTransfer",id,Json.map("state","expired","expires",0L,"resourceId",id));
    }
    private ResourceRepository repository(org.neo4j.graphdb.Transaction tx){return new ResourceRepository(tx,new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC)));}
    private Callable<Map<String,Object>> depositCall(String procedure,Map<String,Object> request){
        return ()->{
            String operation=OperationAttemptTest.operationId(server.sessionTime());
            var arguments=ProcedureCatalog.spec(procedure).bindings().stream().map(request::get).toList();
            assertEquals("REGISTERED", OperationAttemptTest.call(database,token,execution,"deposit.action.begin",
                List.of(operation,namespace,procedure,arguments),null,()->{}).get("state"));
            return OperationAttemptTest.call(database,token,execution,procedure,arguments,operation,()->{});
        };
    }
    private Map<String,Object> timed(Map<String,Long> phases,String name,Callable<Map<String,Object>> call)throws Exception {
        long then=System.nanoTime();Map<String,Object> result=uploader.submit(call).get(15,TimeUnit.SECONDS);phases.put(name,(System.nanoTime()-then)/1_000);return result;
    }
    private void verifyContent(String id,String expected)throws Exception {
        ResourceStorage.Content content;
        try(var tx=database.beginTx()){content=repository(tx).content(repository(tx).location(id));}
        var digest=MessageDigest.getInstance("SHA-256");long bytes=0;byte[] buffer=new byte[65536];
        try(var in=server.storage.content.open(content,0,PAYLOAD)){int n;while((n=in.read(buffer))!=-1){digest.update(buffer,0,n);bytes+=n;}}
        assertEquals(PAYLOAD,bytes);assertEquals(expected,HexFormat.of().formatHex(digest.digest()));
    }
    private static Map<String,Object> args(Object... values){var map=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)map.put((String)values[i],values[i+1]);return map;}
}
