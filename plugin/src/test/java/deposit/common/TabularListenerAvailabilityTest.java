package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import deposit.repository.StagedTableRepository.Table;
import deposit.service.TabularService;
import java.nio.file.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.dbms.api.*;
import org.neo4j.graphdb.*;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.monitoring.Monitors;

/** In-process service/storage coverage; no deployed procedure or configuration seam. */
class TabularListenerAvailabilityTest {
    @TempDir Path temporary;
    DatabaseManagementService management;
    GraphDatabaseAPI database;
    ServerContext server;
    ResourceDownloadServer downloads;
    String previousConfig;
    final String namespace=UUID.randomUUID().toString(), actor=UUID.randomUUID().toString();
    final String table=UUID.randomUUID().toString(), scalarTable=UUID.randomUUID().toString();
    final String resource=UUID.randomUUID().toString(), token=ServerContext.randomToken();
    UUID databaseId;

    @BeforeEach void setup()throws Exception {
        previousConfig=System.getProperty("deposit.config");
        Path resources=Files.createDirectory(temporary.resolve("resources"));
        Path exports=Files.createDirectory(temporary.resolve("exports"));
        Path config=temporary.resolve("deposit.properties");
        Files.writeString(config,"resourceRoot="+resources.toString().replace('\\','/')+"\nexportRoot="+exports.toString().replace('\\','/')+"\ndownload.listenAddress=127.0.0.1\ndownload.port=0\ndownload.publicBaseUrl=http://127.0.0.1:1\n");
        System.setProperty("deposit.config",config.toString());
        // Filter the launcher's extension in this embedded fixture: the test
        // explicitly owns admission, listener and storage instead of provisioning
        // unrelated shutdown-control files or bypassing their production ACLs.
        management=new DatabaseManagementServiceBuilderImplementation(temporary.resolve("database"),
            type->type!=DepositExtension.class).build();
        database=(GraphDatabaseAPI)management.database("neo4j");
        databaseId=database.databaseId().databaseId().uuid();
        // This fixture owns its service context, avoiding launcher/readiness APIs.
        server=ServerContext.attach(databaseId);
        server.storage=new StorageManager(server,database);
        database.getDependencyResolver().resolveDependency(Monitors.class).addMonitorListener(server);
        server.markReadyForTesting();
        downloads=new ResourceDownloadServer(server,database);
        downloads.start();
        server.attachDownloadsForTesting(downloads);
        server.downloadPublicBaseUrl=downloads.publicBaseUrl();
        Path input=resources.resolve("staging/input.bin");Files.write(input,new byte[]{1,2,3});
        var content=new ResourceStorage.Content(resource,"input.bin",3L,ResourceFiles.checksum(input));
        server.storage.content.publish(content,input);server.storage.content.verify(content);
        try(var tx=database.beginTx()) {
            var internal=(InternalTransaction)tx;
            var session=new ServerContext.Session(actor,internal.securityContext().subject().executingUser(),server.sessionTime());
            session.namespace=namespace;session.administrative=true;server.registerSession(Json.hash(token),session);
            node(tx,"User",Json.map("id",actor,"enabled",true,"serverRole","administrator"));
            node(tx,"Namespace",Json.map("id",namespace,"sequence",0L));
            var timestamp=java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC);
            var core=new deposit.repository.CoreRepository(tx,timestamp);
            new deposit.repository.ResourceRepository(tx,core).complete(namespace,"input.bin","application/octet-stream",content,actor,timestamp);
            stagedTable(tx,table,true);stagedTable(tx,scalarTable,false);
            tx.commit();
        }
    }
    static Node node(Transaction tx,String label,Map<String,Object> properties){Node n=tx.createNode(Label.label(label));properties.forEach(n::setProperty);return n;}
    void stagedTable(Transaction tx,String id,boolean links)throws Exception {
        var columns=List.of(Json.map("name","value","type",links?"DResource":"String"));
        Object value=links?Json.map("resourceId",resource):"scalar";
        Path path=server.storage.files.path(Table.path(id));
        Files.createDirectories(path.getParent());
        Files.writeString(path,Json.write(Json.map("columns",columns,"rows",List.of(Json.map("rowId","1","values",Json.map("value",value),"presence",Json.map("value",true))))));
        server.storage.tables.create(new Table(id,namespace,actor,UUID.randomUUID().toString(),
            server.sessionTime(),server.sessionTime()+60_000,Json.write(columns),1,true),null);
    }
    @AfterEach void cleanup() {
        try {
            if(database!=null&&server!=null)database.getDependencyResolver().resolveDependency(Monitors.class).removeMonitorListener(server);
            if(downloads!=null)downloads.close();if(server!=null&&server.storage!=null)server.storage.close();
        }
        finally {try{if(management!=null)management.shutdown();}finally{if(databaseId!=null)ServerContext.detach(databaseId);if(previousConfig==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previousConfig);}}
    }
    @SuppressWarnings("unchecked") Map<String,Object> save(String format,boolean links,String operation) {
        try(var tx=database.beginTx()) {
            var context=new RequestContext(tx,Json.map("token",token,"operationId",operation));
            var request=Json.map("source",Json.map("tableId",links?table:scalarTable),"parameters",Map.of(),"formatOptions",Json.map("filename","export."+format));
            var result=(Map<String,Object>)new TabularService(context).execute("deposit.save."+format,request);
            tx.commit();
            return result;
        }
    }
    List<Long> counts(){
        long resources;
        try(var tx=database.beginTx();var nodes=tx.findNodes(Label.label("DResource"))){resources=nodes.stream().count();}
        return server.storage.operational.read(db->{
            var counts=new ArrayList<Long>();
            counts.add(resources);
            try(var sql=db.createStatement()){
                for(String source:List.of("resource_transfers","export_grants","operation_attempts WHERE receipt_revision IS NOT NULL"))
                    try(var rows=sql.executeQuery("SELECT count(*) FROM "+source)){assertTrue(rows.next());counts.add(rows.getLong(1));}
            }
            return counts;
        });
    }
    Set<Path> privateStaging()throws Exception{try(var paths=Files.walk(server.storage.files.root.resolve("staging"))){return paths.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toSet());}}
    Set<ResourceStorage.Content> published()throws Exception{return new HashSet<>(server.storage.content.discover());}
    void assertUnavailable(String format)throws Exception {
        var beforeCounts=counts();var beforePublished=published();var beforePrivate=privateStaging();
        var failure=assertThrows(ExceptionHandler.DepositException.class,()->save(format,true,UUID.randomUUID().toString()));
        var payload=Json.read(failure.getMessage().substring("DEPOSIT_ERROR ".length()));
        assertEquals("VALIDATION_FAILED",payload.get("code"));assertEquals("Resource download listener is unavailable",payload.get("message"));
        assertEquals(beforeCounts,counts(),"No output resource/location/grant/receipt may be committed");
        assertEquals(beforePublished,published(),"No physical object may be published before rejection");
        assertEquals(beforePrivate,privateStaging(),"Private format staging is cleaned separately from publication");
    }
    @ParameterizedTest @ValueSource(strings={"csv","xlsx","gpkg"})
    void unavailableListenerRejectsLinksButAllowsScalarExports(String format)throws Exception {
        downloads.stopAccepting();assertFalse(downloads.accepting());assertNotNull(server.downloadPublicBaseUrl);
        assertUnavailable(format);
        var result=save(format,false,UUID.randomUUID().toString());
        assertTrue(server.storage.content.exists((String)result.get("resourceId")));
    }
    @ParameterizedTest @ValueSource(strings={"csv","xlsx","gpkg"})
    void listenerStoppedBetweenSerializationAndPublicationLeavesNoPublishedObject(String format)throws Exception {
        var reached=new AtomicBoolean();
        server.storageObserverForTesting(point->{if(point.equals("tabular-export-before-publication")){
            reached.set(true);
            // A separate lifecycle thread must stop without waiting on serialization.
            var executor=Executors.newSingleThreadExecutor();
            try {
                executor.submit(downloads::stopAccepting).get(2,TimeUnit.SECONDS);
            } catch(Exception failure) { throw new AssertionError("Stop blocked behind serialization",failure); }
            finally { executor.shutdownNow(); }
        }});
        assertUnavailable(format);assertTrue(reached.get(),"Stop occurs only after successful serialization");
    }
    @ParameterizedTest @ValueSource(strings={"csv","xlsx","gpkg"})
    void committedReceiptAndObjectRemainWithListenerUnavailable(String format)throws Exception {
        String operation=UUID.randomUUID().toString();
        var result=save(format,true,operation);
        downloads.stopAccepting();
        assertTrue(server.storage.content.exists((String)result.get("resourceId")));
        try(var tx=database.beginTx()){
            var receipt=server.storage.operations.findReceipt(actor,namespace,operation,null);
            assertNotNull(receipt);
        }
    }
    @Test void missingListenerFailsClosedDespiteRetainedConfiguredUrl()throws Exception {
        server.attachDownloadsForTesting(null);
        assertUnavailable("csv");
        assertThrows(ExceptionHandler.DepositException.class,()->server.resourceLinkPublication(true));
        assertNull(server.resourceLinkPublication(false));
    }
    @Test void stopCoordinatesOnlyFinalPublicationAndNotDrain()throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        try(var publication=server.resourceLinkPublication(true)) {
            var started=new CountDownLatch(1);
            Future<?> stop=executor.submit(()->{started.countDown();downloads.stopAccepting();});
            assertTrue(started.await(2,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->stop.get(100,TimeUnit.MILLISECONDS));
        } finally {executor.shutdown();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));}
        assertFalse(downloads.accepting());assertThrows(ExceptionHandler.DepositException.class,()->server.resourceLinkPublication(true));
    }
}
