package deposit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import deposit.common.*;
import deposit.repository.CoreRepository;
import deposit.repository.ResourceRepository;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.time.ZonedDateTime;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.IOException;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilderImplementation;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

class ImportSnapshotMaterializerTest {
    @TempDir Path temporary;

    DatabaseManagementService management;
    GraphDatabaseAPI database;
    ServerContext server;
    String previousConfig;

    @BeforeEach void setup()throws Exception {
        previousConfig=System.getProperty("deposit.config");Path resourceRoot=Files.createDirectories(temporary.resolve("server-resources")),exportRoot=Files.createDirectories(temporary.resolve("server-exports"));Path config=temporary.resolve("deposit-test.properties");Properties properties=new Properties();properties.setProperty("resourceRoot",resourceRoot.toString());properties.setProperty("exportRoot",exportRoot.toString());try(var out=Files.newOutputStream(config)){properties.store(out,"test");}System.setProperty("deposit.config",config.toString());
        management=new DatabaseManagementServiceBuilderImplementation(temporary.resolve("db"),type->type!=DepositExtension.class).build();database=(GraphDatabaseAPI)management.database("neo4j");var databaseId=database.databaseId().databaseId().uuid();server=ServerContext.attach(databaseId);var field=ServerContext.class.getDeclaredField("database");field.setAccessible(true);field.set(server,database);server.storage=new StorageManager(server,database);var ready=ServerContext.class.getDeclaredMethod("markReadyForTesting");ready.setAccessible(true);ready.invoke(server);database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).addMonitorListener(server);management.registerTransactionEventListener("neo4j",server);
    }

    @AfterEach void teardown(){if(database!=null&&server!=null){management.unregisterTransactionEventListener("neo4j",server);database.getDependencyResolver().resolveDependency(org.neo4j.monitoring.Monitors.class).removeMonitorListener(server);}if(server!=null&&server.storage!=null)server.storage.close();if(management!=null)management.shutdown();if(database!=null)ServerContext.detach(database.databaseId().databaseId().uuid());if(previousConfig==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previousConfig);}

    @Test void applyKeepsEquivalentMatchedResourceReferencesAndReturnsNoResourceMappings()throws Exception {
        byte[] content="shared snapshot bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);String checksum=ResourceFiles.checksum(content),sourceResource=UUID.randomUUID().toString(),actor=UUID.randomUUID().toString(),namespace;
        Path archive=archive("apply-package.zip",checksum,content);
        try(var tx=database.beginTx()){
            var repo=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));repo.createRestored("User",null,actor,Map.of(),actor);var ns=repo.create("Namespace",null,actor,Json.map("name","Apply target","description","","sequence",0L,"historyMode","disabled"));namespace=CoreRepository.id(ns);repo.setRole(actor,namespace,"owner");
            var clazz=repo.create("Class",namespace,actor,Json.map("name","Thing","position",0L));repo.attach("ClassDescriptor",clazz,actor,Json.map("name","File","type","DResource","order",0L));
            var objectOne=repo.create("Object",namespace,actor,Json.map("position",List.of(0.0,0.0)));repo.assign(objectOne,clazz,actor);var objectTwo=repo.create("Object",namespace,actor,Json.map("position",List.of(1.0,1.0)));repo.assign(objectTwo,clazz,actor);
            var existingOne=repo.attach("Descriptor",objectOne,actor,Json.map("name","File"));var existingTwo=repo.attach("Descriptor",objectTwo,actor,Json.map("name","File"));String targetResourceOne=UUID.randomUUID().toString(),targetResourceTwo=UUID.randomUUID().toString();var resourceRepo=new ResourceRepository(tx,repo);seedResource(resourceRepo,namespace,actor,targetResourceOne,checksum,content.length);seedResource(resourceRepo,namespace,actor,targetResourceTwo,checksum,content.length);existingOne.createRelationshipTo(repo.entity("DResource",namespace,targetResourceOne),RelationshipType.withName("HAS_RESOURCE"));existingTwo.createRelationshipTo(repo.entity("DResource",namespace,targetResourceTwo),RelationshipType.withName("HAS_RESOURCE"));
            var graph=graph(List.of(Json.map("id","source-class","name","Thing","position",0L,"order",0L,"comment","","descriptors",List.of(Json.map("name","File","type","DResource","order",0L,"comment","")))),List.of(Json.map("id","source-object-1","classId","source-class","position",List.of(0.0,0.0)),Json.map("id","source-object-2","classId","source-class","position",List.of(1.0,1.0))),List.of(Json.map("id","descriptor-1","objectId","source-object-1","name","File","value",Json.map("resourceId",sourceResource)),Json.map("id","descriptor-2","objectId","source-object-2","name","File","value",Json.map("resourceId",sourceResource))),List.of(resourceRecord(sourceResource,checksum,content.length)));
            var context=new RequestContext(tx,Json.map("operationId",UUID.randomUUID().toString()));context.actorId=actor;context.namespaceId=namespace;Map<String,Object> result=new ImportSnapshotMaterializer(context,repo,new ResourceRepository(tx,repo)).apply(new ExchangePackageCodec.PackageData(Json.map("sourceNamespaceId","source-ns"),graph,Map.of()),archive,namespace,Map.of("Thing","Thing"),Map.of("source-object-1",CoreRepository.id(objectOne),"source-object-2",CoreRepository.id(objectTwo)),Map.of());
            assertEquals(Map.of(),ValueCodec.map(result.get("resourceMapping")));assertEquals(Map.of(),ValueCodec.map(ValueCodec.map(result.get("identityMappings")).get("DResource")));assertEquals(0L,result.get("createdResources"));assertFalse(ValueCodec.map(result.get("mapping")).containsKey(sourceResource));assertFalse(server.storage.content.exists(sourceResource));assertEquals(targetResourceOne,CoreRepository.id(new ResourceRepository(tx,repo).linked(existingOne)));assertEquals(targetResourceTwo,CoreRepository.id(new ResourceRepository(tx,repo).linked(existingTwo)));
            tx.commit();
        }
    }

    @Test void directMaterializeIncludesResourcesOutsideCurrentDescriptorsForClone()throws Exception {
        byte[] content="history snapshot bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);String checksum=ResourceFiles.checksum(content),sourceResource=UUID.randomUUID().toString(),actor=UUID.randomUUID().toString();Path archive=archive("clone-package.zip",checksum,content);
        try(var tx=database.beginTx()){
            var repo=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));repo.createRestored("User",null,actor,Map.of(),actor);var ns=repo.create("Namespace",null,actor,Json.map("name","Clone target","description","","sequence",0L,"historyMode","disabled"));String namespace=CoreRepository.id(ns);repo.setRole(actor,namespace,"owner");
            var context=new RequestContext(tx,Json.map("operationId",UUID.randomUUID().toString()));context.actorId=actor;context.namespaceId=namespace;var graph=graph(List.of(),List.of(),List.of(),List.of(resourceRecord(sourceResource,checksum,content.length)));
            Map<String,Object> result=new ImportSnapshotMaterializer(context,repo,new ResourceRepository(tx,repo)).materialize(new ExchangePackageCodec.PackageData(Json.map("sourceNamespaceId","source-ns"),graph,Map.of()),archive,namespace,Map.of(),Map.of());
            String mapped=(String)ValueCodec.map(result.get("resourceMapping")).get(sourceResource);assertNotNull(mapped);assertEquals(mapped,ValueCodec.map(ValueCodec.map(result.get("identityMappings")).get("DResource")).get(sourceResource));assertEquals(mapped,ValueCodec.map(result.get("mapping")).get(sourceResource));assertEquals(1L,result.get("createdResources"));assertNotNull(repo.entity("DResource",namespace,mapped));
            tx.commit();
        }
    }

    @Test void rollbackAfterPublicationRetainsReservationForOrphanReconciliation()throws Exception {
        byte[] content="uncommitted publication bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);String checksum=ResourceFiles.checksum(content),sourceResource=UUID.randomUUID().toString(),actor=UUID.randomUUID().toString();Path archive=archive("rollback-publication.zip",checksum,content);
        String operation=UUID.randomUUID().toString();
        try(var tx=database.beginTx()){
            var repo=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));repo.createRestored("User",null,actor,Map.of(),actor);var ns=repo.create("Namespace",null,actor,Json.map("name","Rollback target","description","","sequence",0L,"historyMode","disabled"));String namespace=CoreRepository.id(ns);repo.setRole(actor,namespace,"owner");
            var context=new RequestContext(tx,Json.map("operationId",operation));context.actorId=actor;context.namespaceId=namespace;
            var graph=graph(List.of(),List.of(),List.of(),List.of(resourceRecord(sourceResource,checksum,content.length)));
            new ImportSnapshotMaterializer(context,repo,new ResourceRepository(tx,repo)).materialize(new ExchangePackageCodec.PackageData(Json.map("sourceNamespaceId","source-ns"),graph,Map.of()),archive,namespace,Map.of(),Map.of());
            tx.rollback();
        }
        try(var paths=Files.list(server.storage.files.root.resolve("admission"))){
            var rows=paths.toList();for(Path record:rows)System.out.println("ROLLBACK ADMISSION="+Files.readString(record));System.out.println("ROLLBACK_DIAGNOSTIC="+server.storage.diagnostic);assertEquals(2,rows.size(),"publication must not erase independent admission ownership on rollback");
            assertEquals(1,server.storage.content.discover().size());for(Path path:rows){var record=Json.read(Files.readString(path));assertEquals(operation,record.get("operationId"));}
        }
    }

    @Test void applyMaterializesOnlyResourcesForCurrentDescriptorWrites()throws Exception {
        byte[] currentBytes="current descriptor content".getBytes(java.nio.charset.StandardCharsets.UTF_8),historyBytes="history only content".getBytes(java.nio.charset.StandardCharsets.UTF_8);String currentChecksum=ResourceFiles.checksum(currentBytes),historyChecksum=ResourceFiles.checksum(historyBytes),currentSource=UUID.randomUUID().toString(),historySource=UUID.randomUUID().toString(),actor=UUID.randomUUID().toString();Path archive=archive("mixed-package.zip",Map.of(currentChecksum,currentBytes,historyChecksum,historyBytes));
        try(var tx=database.beginTx()){
            var repo=new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC));repo.createRestored("User",null,actor,Map.of(),actor);var ns=repo.create("Namespace",null,actor,Json.map("name","Selective apply target","description","","sequence",0L,"historyMode","disabled"));String namespace=CoreRepository.id(ns);repo.setRole(actor,namespace,"owner");
            var context=new RequestContext(tx,Json.map("operationId",UUID.randomUUID().toString()));context.actorId=actor;context.namespaceId=namespace;
            var graph=graph(List.of(Json.map("id","source-class","name","Thing","position",0L,"order",0L,"comment","","descriptors",List.of(Json.map("name","File","type","DResource","order",0L,"comment","")))),List.of(Json.map("id","new-source-object","classId","source-class","position",List.of(0.0,0.0))),List.of(Json.map("id","new-source-descriptor","objectId","new-source-object","name","File","value",Json.map("resourceId",currentSource))),List.of(resourceRecord(currentSource,currentChecksum,currentBytes.length),resourceRecord(historySource,historyChecksum,historyBytes.length)));
            var noExistingMappings=new java.util.LinkedHashMap<String,Object>();noExistingMappings.put("new-source-object",null);Map<String,Object> result=new ImportSnapshotMaterializer(context,repo,new ResourceRepository(tx,repo)).apply(new ExchangePackageCodec.PackageData(Json.map("sourceNamespaceId","source-ns"),graph,Map.of()),archive,namespace,Map.of("Thing","Thing"),noExistingMappings,Map.of());
            String mapped=(String)ValueCodec.map(result.get("resourceMapping")).get(currentSource);assertNotNull(mapped);assertEquals(Set.of(currentSource),ValueCodec.map(result.get("resourceMapping")).keySet());assertEquals(Set.of(currentSource),ValueCodec.map(ValueCodec.map(result.get("identityMappings")).get("DResource")).keySet());assertEquals(mapped,ValueCodec.map(result.get("mapping")).get(currentSource));assertFalse(ValueCodec.map(result.get("mapping")).containsKey(historySource));assertEquals(1L,result.get("createdResources"));
            String mappedObject=(String)ValueCodec.map(ValueCodec.map(result.get("identityMappings")).get("Object")).get("new-source-object");var descriptor=repo.descriptor(repo.entity("Object",namespace,mappedObject),"File");assertNotNull(descriptor);assertEquals(mapped,CoreRepository.id(new ResourceRepository(tx,repo).linked(descriptor)));assertTrue(server.storage.content.exists(mapped));assertFalse(server.storage.content.exists(historySource));
            tx.commit();
        }
    }

    private Path archive(String name,String checksum,byte[] content)throws IOException {Path path=temporary.resolve(name);try(OutputStream file=Files.newOutputStream(path);ZipOutputStream zip=new ZipOutputStream(file)){zip.putNextEntry(new ZipEntry("resources/"+checksum));zip.write(content);zip.closeEntry();}return path;}
    private Path archive(String name,Map<String,byte[]> entries)throws IOException {Path path=temporary.resolve(name);try(OutputStream file=Files.newOutputStream(path);ZipOutputStream zip=new ZipOutputStream(file)){for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry("resources/"+entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}return path;}
    private static void seedResource(ResourceRepository repo,String namespace,String actor,String id,String checksum,long bytes){repo.record("DResource",id,Json.map("namespaceId",namespace,"filename","data.bin","storedFilename",ResourceFiles.filename("data.bin",checksum),"mediaType","application/octet-stream","bytes",bytes,"checksum",checksum,"state","ready","createdBy",actor));}
    private static Map<String,Object> resourceRecord(String id,String checksum,long bytes){return Json.map("id",id,"sha256",checksum,"bytes",bytes,"filename","data.bin","mediaType","application/octet-stream");}
    private static Map<String,Object> graph(List<Object> classes,List<Object> objects,List<Object> descriptors,List<Object> resources){return Json.map("classes",classes,"objects",objects,"descriptors",descriptors,"resources",resources,"memberships",List.of(),"relationships",List.of(),"classRelationships",List.of(),"patterns",List.of(),"userTools",List.of());}

    @Test void existingTargetReachabilityUsesOnlyTypedCurrentStateResourceDescriptors() {
        var graph = Json.map(
            "classes", List.of(Json.map("id", "class-1", "descriptors", List.of(
                Json.map("name", "File", "type", "DResource"),
                Json.map("name", "Text", "type", "String")))),
            "objects", List.of(Json.map("id", "object-1", "classId", "class-1")),
            "descriptors", List.of(
                Json.map("objectId", "object-1", "name", "File", "value", Json.map("resourceId", "current-resource")),
                Json.map("objectId", "object-1", "name", "Text", "value", Json.map("resourceId", "untyped-decoy"))),
            "resources", List.of(
                Json.map("id", "current-resource"),
                Json.map("id", "history-only-resource")));

        assertEquals(Set.of("current-resource"), ImportSnapshotMaterializer.currentDescriptorResourceIds(graph));
    }

    @Test void diskBackedReachabilityAndOrderedMappingsAvoidPackageSizedHashMaps()throws IOException {
        var objects=new java.util.ArrayList<Object>();var descriptors=new java.util.ArrayList<Object>();
        for(int i=0;i<2_000;i++){String id="object-"+i;objects.add(Json.map("id",id,"classId","class-1"));descriptors.add(Json.map("objectId",id,"name","File","value",i==1_999?Json.map("resourceId","current-resource"):null));}
        var graph=Json.map("classes",List.of(Json.map("id","class-1","descriptors",List.of(Json.map("name","File","type","DResource")))),"objects",objects,"descriptors",descriptors,"resources",List.of());
        try(var scratch=new ImportScratch(temporary,()->{})){
            Set<String> reachable=ImportSnapshotMaterializer.currentDescriptorResourceIds(graph,scratch);
            assertEquals(1,reachable.size());assertEquals(Set.of("current-resource"),Set.of(reachable.iterator().next()));
            var mapping=scratch.<String>orderedMap();for(int i=1_999;i>=0;i--)mapping.put("source-"+i,"target-"+i);
            var keys=mapping.keySet().iterator();assertEquals("source-0",keys.next());assertEquals("source-1",keys.next());assertEquals(2_000,mapping.size());
        }
    }
}
