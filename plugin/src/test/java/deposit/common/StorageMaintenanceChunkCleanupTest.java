package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import deposit.repository.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.*;
import org.neo4j.graphdb.Label;import org.neo4j.graphdb.Node;import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/** Direct terminal-chunk cleanup regression against a real embedded graph. */
class StorageMaintenanceChunkCleanupTest {
    @TempDir Path temporary;
    private DatabaseManagementService management;
    private GraphDatabaseAPI database;
    private ServerContext server;
    private String previousConfig,actor,namespace;

    @BeforeEach void setUp()throws Exception {
        previousConfig=System.getProperty("deposit.config");
        Path root=Files.createDirectories(temporary.resolve("resources")),exports=Files.createDirectories(temporary.resolve("exports")),config=temporary.resolve("deposit-test.properties");
        var properties=new Properties();properties.setProperty("resourceRoot",root.toString());properties.setProperty("exportRoot",exports.toString());
        try(var out=Files.newOutputStream(config)){properties.store(out,"terminal chunk cleanup");}System.setProperty("deposit.config",config.toString());
        management=new DatabaseManagementServiceBuilderImplementation(temporary.resolve("database"),type->type!=DepositExtension.class).build();
        database=(GraphDatabaseAPI)management.database("neo4j");server=ServerContext.attach(database.databaseId().databaseId().uuid());
        var field=ServerContext.class.getDeclaredField("database");field.setAccessible(true);field.set(server,database);server.storage=new StorageManager(server,database);SchemaRepository.initialize(database);
        var ready=ServerContext.class.getDeclaredMethod("markReadyForTesting");ready.setAccessible(true);ready.invoke(server);
        database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).addMonitorListener(server);management.registerTransactionEventListener("neo4j",server);
        actor=UUID.randomUUID().toString();
        try(var tx=database.beginTx()){
            var repo=new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC));repo.createRestored("User",null,actor,Json.map("enabled",true,"serverRole","user"),actor);
            Node ns=repo.create("Namespace",null,actor,Json.map("name","Chunk cleanup regression","description","","sequence",0L,"historyMode","disabled"));namespace=CoreRepository.id(ns);repo.setRole(actor,namespace,"owner");tx.commit();
        }
    }
    @AfterEach void tearDown(){
        try{if(database!=null&&server!=null){management.unregisterTransactionEventListener("neo4j",server);database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).removeMonitorListener(server);}}
        finally{try{if(server!=null)server.shutdown();}finally{try{if(management!=null)management.shutdown();}finally{if(database!=null)ServerContext.detach(database.databaseId().databaseId().uuid());if(previousConfig==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previousConfig);}}}
    }

    @Test void noTerminalChunkSkipsExpensiveDelete()throws Exception {
        try(var real=database.beginTx()){
            var recorded=new Queries(real);long start=System.nanoTime();
            assertEquals(0,StorageMaintenanceJobs.collectTerminalTransferChunks(recorded.transaction(),256));
            long elapsed=System.nanoTime()-start;report("empty",0,recorded.fullDeletes,recorded.existenceProbes,elapsed);
            assertEquals(0,recorded.fullDeletes,"without terminal chunks, the delete must not execute: "+recorded.executed);
            assertEquals(1,recorded.existenceProbes,recorded.executed.toString());real.commit();
        }
    }
    @Test void uploadingChunkIsRetainedWhileFinishedChunkIsDeleted()throws Exception {
        String uploading=createTransfer("uploading",true);
        String finished=createTransfer("finished",true);
        try(var real=database.beginTx()){
            var recorded=new Queries(real);long start=System.nanoTime();
            assertEquals(1,StorageMaintenanceJobs.collectTerminalTransferChunks(recorded.transaction(),256));long elapsed=System.nanoTime()-start;real.commit();
            assertEquals(1,recorded.fullDeletes);
            assertEquals(1,recorded.existenceProbes);report("uploading-and-finished",1,recorded.fullDeletes,recorded.existenceProbes,elapsed);
        }
        assertEquals(1,chunkCount(uploading));
        assertEquals(0,chunkCount(finished));
    }
    @Test void unprotectedTerminalChunkIsDeleted()throws Exception {
        String upload=createTransfer("finished",false);
        try(var real=database.beginTx()){
            var recorded=new Queries(real);long start=System.nanoTime();
            assertEquals(1,StorageMaintenanceJobs.collectTerminalTransferChunks(recorded.transaction(),256));long elapsed=System.nanoTime()-start;real.commit();
            assertEquals(1,recorded.fullDeletes);assertEquals(1,recorded.existenceProbes);report("deleted",1,recorded.fullDeletes,recorded.existenceProbes,elapsed);
        }
        assertEquals(0,chunkCount(upload));
    }

    private String createTransfer(String state,boolean pending){
        String id=UUID.randomUUID().toString();
        try(var tx=database.beginTx()){
            Node transfer=tx.createNode(Label.label("DepositTransfer"));transfer.setProperty("id",id);transfer.setProperty("state",state);transfer.setProperty("actorId",actor);transfer.setProperty("namespaceId",namespace);
            Node chunk=tx.createNode(Label.label("DepositTransferChunk"));chunk.setProperty("id",id+":0");chunk.setProperty("uploadId",id);chunk.setProperty("offset",0L);
            if(pending){Node action=tx.createNode(Label.label("DepositPreparedAction"));action.setProperty("id",UUID.randomUUID().toString());action.setProperty("actorId",actor);action.setProperty("scope",namespace);action.setProperty("resourceUploadId",id);action.setProperty("operationId",UUID.randomUUID().toString());action.setProperty("procedure","deposit.resource.uploadChunk");}
            tx.commit();
        }return id;
    }
    private long chunkCount(String upload){try(var tx=database.beginTx();var rows=tx.findNodes(Label.label("DepositTransferChunk"),"uploadId",upload)){return rows.stream().count();}}
    private void report(String fixture,long deleted,int fullDeletes,int probes,long nanos)throws Exception {
        var result=new LinkedHashMap<String,Object>();result.put("fixture",fixture);result.put("deleted",deleted);result.put("fullDeleteExecutions",fullDeletes);result.put("existenceProbeExecutions",probes);result.put("elapsedNanos",nanos);
        Path output=Path.of(System.getProperty("deposit.chunk.cleanup.report.root",temporary.toString()),"StorageMaintenanceChunkCleanupTest-"+fixture+".json");Files.createDirectories(output.toAbsolutePath().getParent());Files.writeString(output,Json.write(result)+System.lineSeparator(),StandardCharsets.UTF_8);
        System.out.println("CHUNK_CLEANUP fixture="+fixture+" deleted="+deleted+" fullDeletes="+fullDeletes+" probes="+probes+" elapsedMicros="+String.format(Locale.ROOT,"%.3f",nanos/1000.0));
    }
    private static final class Queries {
        final Transaction real;final List<String> executed=new ArrayList<>();int fullDeletes,existenceProbes;
        Queries(Transaction real){this.real=real;}
        Transaction transaction(){return (Transaction)Proxy.newProxyInstance(Transaction.class.getClassLoader(),new Class<?>[]{Transaction.class},(proxy,method,args)->{
            if(method.getName().equals("execute")&&args!=null&&args.length>0&&args[0] instanceof String query){executed.add(query);
                if(query.startsWith("MATCH (transfer:DepositTransfer)")&&query.contains("DELETE chunk RETURN count(*) AS deleted"))fullDeletes++;
                if(query.startsWith("MATCH (transfer:DepositTransfer) WHERE transfer.state IN")&&query.contains("RETURN transfer.id LIMIT 1"))existenceProbes++;
            }
            try{return method.invoke(real,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        });}
    }
}
