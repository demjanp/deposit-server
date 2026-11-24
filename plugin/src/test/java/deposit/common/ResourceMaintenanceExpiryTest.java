package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import deposit.repository.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZonedDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.*;
import org.neo4j.graphdb.Node;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/** Real repository expiry behavior and timing, without schedulers or concurrent workers. */
class ResourceMaintenanceExpiryTest {
    private static final int UPLOADS=128,ROUNDS=8;
    @TempDir Path temporary;
    private DatabaseManagementService management;
    private GraphDatabaseAPI database;
    private ServerContext server;
    private String previousConfig,actor,namespace;

    @BeforeEach void setUp()throws Exception {
        previousConfig=System.getProperty("deposit.config");
        Path root=Files.createDirectories(temporary.resolve("resources")),exports=Files.createDirectories(temporary.resolve("exports")),config=temporary.resolve("deposit-test.properties");
        var properties=new Properties();properties.setProperty("resourceRoot",root.toString());properties.setProperty("exportRoot",exports.toString());
        try(var out=Files.newOutputStream(config)){properties.store(out,"expiry");}System.setProperty("deposit.config",config.toString());
        management=new DatabaseManagementServiceBuilderImplementation(temporary.resolve("database"),type->type!=DepositExtension.class).build();
        database=(GraphDatabaseAPI)management.database("neo4j");server=ServerContext.attach(database.databaseId().databaseId().uuid());
        var field=ServerContext.class.getDeclaredField("database");field.setAccessible(true);field.set(server,database);server.storage=new StorageManager(server,database);SchemaRepository.initialize(database);
        var ready=ServerContext.class.getDeclaredMethod("markReadyForTesting");ready.setAccessible(true);ready.invoke(server);
        database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).addMonitorListener(server);management.registerTransactionEventListener("neo4j",server);
        actor=UUID.randomUUID().toString();
        try(var tx=database.beginTx()){
            var repo=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));repo.createRestored("User",null,actor,Json.map("enabled",true,"serverRole","user"),actor);
            Node ns=repo.create("Namespace",null,actor,Json.map("name","Expiry regression","description","","sequence",0L,"historyMode","disabled"));namespace=CoreRepository.id(ns);repo.setRole(actor,namespace,"owner");tx.commit();
        }
    }
    @AfterEach void tearDown(){
        try{if(database!=null&&server!=null){management.unregisterTransactionEventListener("neo4j",server);database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).removeMonitorListener(server);}}
        finally{try{if(server!=null&&server.storage!=null)server.storage.close();}finally{try{if(management!=null)management.shutdown();}finally{if(database!=null)ServerContext.detach(database.databaseId().databaseId().uuid());if(previousConfig==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previousConfig);}}}
    }

    @Test void activeUploadSkipsOrphanWorkWithoutMutationsAndExpiredUploadStillExpires()throws Exception {
        var active=new ArrayList<String>(UPLOADS);final long expires,lease,orphanSince=123456789L;
        try(var tx=database.beginTx()){
            var resources=repository(tx);expires=resources.now()+ResourceFiles.LEASE;lease=expires;
            for(int i=0;i<UPLOADS;i++)active.add(createUpload(resources,true,expires,lease,orphanSince));
            tx.commit();
        }
        // Warm the actual repository path before recording the timed rounds.
        for(String id:active)expireOne(id);
        long activeStart=System.nanoTime();
        for(int round=0;round<ROUNDS;round++)for(String id:active)expireOne(id);
        long activeNanos=System.nanoTime()-activeStart;
        var expired=new ArrayList<String>(UPLOADS);
        try(var tx=database.beginTx()){
            var resources=repository(tx);
            for(int i=0;i<UPLOADS;i++)expired.add(createUpload(resources,false,resources.now()-1,0,0));
            tx.commit();
        }
        long expiredStart=System.nanoTime();for(String id:expired)expireOne(id);long expiredNanos=System.nanoTime()-expiredStart;

        var report=new LinkedHashMap<String,Object>();report.put("activeUploadsPerRound",UPLOADS);report.put("rounds",ROUNDS);report.put("activeExpireCalls",UPLOADS*ROUNDS);
        report.put("activeTotalNanos",activeNanos);report.put("expiredTotalNanos",expiredNanos);report.put("activeMicrosPerCall",activeNanos/1_000.0/(UPLOADS*ROUNDS));report.put("expiredMicrosPerCall",expiredNanos/1_000.0/UPLOADS);
        Path output=Path.of(System.getProperty("deposit.expiry.report.root",temporary.toString()),"ResourceMaintenanceExpiryTest-results.json");Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output,Json.write(report)+System.lineSeparator(),StandardCharsets.UTF_8);
        System.out.println("EXPIRY activeCalls="+UPLOADS*ROUNDS+" activeTotalMs="+String.format(Locale.ROOT,"%.3f",activeNanos/1_000_000.0)+" expiredCalls="+UPLOADS+" expiredTotalMs="+String.format(Locale.ROOT,"%.3f",expiredNanos/1_000_000.0)+" results="+output);

        try(var tx=database.beginTx()){
            var resources=repository(tx);
            for(String id:active){
                var transfer=resources.location(id);
                assertEquals("uploading",transfer.state());
                assertEquals(expires,transfer.expires());
                assertEquals(orphanSince,transfer.orphanSince(),"active lease must return before orphan maintenance writes");
            }
            for(String id:expired){
                var transfer=resources.location(id);
                assertEquals("expired",transfer.state());
                assertNotNull(transfer.orphanSince(),"expired upload must retain its orphan age");
            }
            tx.rollback();
        }
    }
    private String createUpload(ResourceRepository resources,boolean active,long expires,long lease,long orphanSince){
        String id=UUID.randomUUID().toString(),upload=UUID.randomUUID().toString();
        server.storage.transfers.save(new ResourceTransferStore.Transfer(upload,id,namespace,actor,"test","test","test",
            0L,ResourceFiles.checksum(new byte[0]),0L,"uploading",active?lease:expires,null,null,false,
            orphanSince==0?null:orphanSince,null,false,false,null),"");
        return id;
    }
    private void expireOne(String id){try(var tx=database.beginTx()){repository(tx).expire(id);tx.commit();}}
    private ResourceRepository repository(org.neo4j.graphdb.Transaction tx){return new ResourceRepository(tx,new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC)));}
}
