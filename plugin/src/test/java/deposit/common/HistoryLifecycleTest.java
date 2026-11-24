package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import deposit.repository.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.*;
import org.neo4j.graphdb.*;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;

class HistoryLifecycleTest {
    @TempDir Path temporary;
    DatabaseManagementService management;GraphDatabaseAPI database;ServerContext server;StorageManager storage;String previousConfig;
    @BeforeEach void setup()throws Exception{
        previousConfig=System.getProperty("deposit.config");Path resources=Files.createDirectories(temporary.resolve("resources")),exports=Files.createDirectories(temporary.resolve("exports")),config=temporary.resolve("deposit.properties");var properties=new Properties();properties.setProperty("resourceRoot",resources.toString());properties.setProperty("exportRoot",exports.toString());try(var out=Files.newOutputStream(config)){properties.store(out,"test");}System.setProperty("deposit.config",config.toString());
        management=new DatabaseManagementServiceBuilderImplementation(temporary.resolve("db"),type->type!=DepositExtension.class).build();database=(GraphDatabaseAPI)management.database("neo4j");server=ServerContext.attach(database.databaseId().databaseId().uuid());var field=ServerContext.class.getDeclaredField("database");field.setAccessible(true);field.set(server,database);storage=new StorageManager(server,database);server.storage=storage;SchemaRepository.initialize(database);server.markReadyForTesting();database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).addMonitorListener(server);
    }
    @AfterEach void close(){if(database!=null&&server!=null)database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).removeMonitorListener(server);if(storage!=null)storage.close();if(management!=null)management.shutdown();if(database!=null)ServerContext.detach(database.databaseId().databaseId().uuid());if(previousConfig==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previousConfig);}
    @Test void transientRestartCreatesAdmittedDiskBaselineAfterEveryExpiredEpochIsCleaned()throws Exception{
        String namespace,actor=UUID.randomUUID().toString();
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC));Node root=core.create("Namespace",null,actor,Json.map("name","Transient","description","","historyMode","transient","sequence",0L));namespace=CoreRepository.id(root);
            for(int i=0;i<300;i++)core.create("UserTool",namespace,actor,Json.map("name","Tool"+i,"data","opaque "+i,"comment",""));
            var history=new HistoryRepository(tx,core);history.baseline(namespace,actor,0,"old-process",ZonedDateTime.now(ZoneOffset.UTC));history.baseline(namespace,actor,0,"old-process",ZonedDateTime.now(ZoneOffset.UTC));tx.commit();
        }
        HistoryLifecycle.recover(server);
        try(var tx=database.beginTx()){
            var core=new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC));var history=new HistoryRepository(tx,core);Node root=core.namespace(namespace);Revision baseline=history.revision(namespace,(String)root.getProperty("headRevisionId"));assertEquals("baseline",baseline.kind());assertEquals(301,baseline.after().size());assertEquals(301,baseline.touches().size());assertEquals(core.namespace(namespace).getProperty("historyEpochId"),baseline.data().get("historyEpochId"));
            assertEquals(Json.write(history.capture(namespace)),Json.write(HistoryPlanner.reconstruct(List.of(baseline),baseline.id())));
            for(String label:List.of("Revision","DepositHistoryEpoch"))try(var nodes=core.scanNodes(label,Map.of("namespaceId",namespace))){assertEquals(1,nodes.stream().count(),label);}
            try(var epochs=core.scanNodes("DepositHistoryEpoch",Map.of("namespaceId",namespace))){assertEquals(server.runId,epochs.next().getProperty("bootId"));}
        }
        try(var reservations=Files.list(storage.files.root.resolve("admission"))){assertEquals(0,reservations.count());}
        try(var scratch=Files.list(storage.files.path("staging/imports"))){assertEquals(0,scratch.filter(p->p.getFileName().toString().startsWith("history-index-")).count());}
    }
    @Test void diskImportHistoryRetainsRealResourceForPortableExportAndUndo()throws Exception{
        String actor=UUID.randomUUID().toString(),resource=UUID.randomUUID().toString(),namespace,revision;
        Path input=storage.files.path("staging/history.bin");Files.writeString(input,"retained history bytes");String checksum=ResourceFiles.checksum(input),stored=ResourceFiles.filename("history.bin",checksum);var content=new ResourceStorage.Content(resource,stored,Files.size(input),checksum);storage.content.publish(content,input);
        try(var tx=database.beginTx();var workspace=new HistoryWorkspace(storage.files.path("staging/imports"),()->{},ignored->{},()->{})){
            var kernel=((InternalTransaction)tx).kernelTransaction();var core=new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC));var history=new HistoryRepository(tx,core);var resources=new ResourceRepository(tx,core);
            core.createRestored("User",null,actor,Json.map("enabled",true,"serverRole","user"),actor);Node root=core.create("Namespace",null,actor,Json.map("name","Resources","description","","historyMode","persistent","sequence",0L));namespace=CoreRepository.id(root);core.setRole(actor,namespace,"owner");Node clazz=core.create("Class",namespace,actor,Json.map("name","Thing","position",List.of(0.0,0.0)));core.attach("ClassDescriptor",clazz,actor,Json.map("name","File","type","DResource"));Node object=core.create("Object",namespace,actor,Json.map("position",List.of(0.0,0.0)));core.assign(object,clazz,actor);Node descriptor=core.attach("Descriptor",object,actor,Json.map("name","File","encoding","resource"));
            Node saved=resources.complete(namespace,"history.bin","application/octet-stream",content,actor,ZonedDateTime.now(ZoneOffset.UTC));
            assertEquals(root,saved.getSingleRelationship(RelationshipType.withName("OWNS_RESOURCE"),Direction.INCOMING).getStartNode());
            assertNull(resources.location(resource));assertFalse(resources.accessible(saved,actor,"owner",false));
            descriptor.createRelationshipTo(saved,RelationshipType.withName("HAS_RESOURCE"));
            history.baseline(namespace,actor,0,server.runId,ZonedDateTime.now(ZoneOffset.UTC));var journal=workspace.journal(history.capture(namespace,workspace.newState()));core.writeValue(descriptor,"DResource",null,actor);core.advanceNamespace(root,CoreRepository.sequence(root),actor);journal.checkpoint(history.capture(namespace,workspace.newState()));revision=history.appendId(namespace,actor,UUID.randomUUID().toString(),1,"import","Resource replacement",journal,null,null,ZonedDateTime.now(ZoneOffset.UTC),null);assertFalse(resources.live(resource));assertTrue(resources.retained(resource));assertTrue(resources.accessible(saved,actor,"owner",false));tx.commit();server.commit(kernel);
        }
        try(var tx=database.beginTx();var records=new ExportRecords(storage.files.path("staging/imports"))){
            var kernel=((InternalTransaction)tx).kernelTransaction();var context=new RequestContext(tx,Map.of());context.namespaceId=namespace;context.actorId=actor;String wire=Json.write(new deposit.service.HistoryService(context).historyExport(records));assertTrue(wire.contains(resource));assertFalse(wire.contains("touchedFields"));assertFalse(wire.contains("leaseUntil"));var core=new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC));var history=new HistoryRepository(tx,core);var selected=history.revision(namespace,revision);var plan=HistoryPlanner.inverse(1,history.capture(namespace),selected,history.revisions(namespace));assertTrue(plan.conflicts().isEmpty());history.apply(namespace,actor,plan);assertTrue(new ResourceRepository(tx,core).live(resource));tx.commit();server.commit(kernel);
        }
        try(var bytes=storage.content.open(content,0,content.bytes())){assertEquals("retained history bytes",new String(bytes.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
    }
}
