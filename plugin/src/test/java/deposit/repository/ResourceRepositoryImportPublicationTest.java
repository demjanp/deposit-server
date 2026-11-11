package deposit.repository;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilderImplementation;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

class ResourceRepositoryImportPublicationTest {
    @TempDir Path temporary;
    DatabaseManagementService management;
    GraphDatabaseAPI database;
    ServerContext server;
    StorageManager storage;
    String previousConfig;

    @BeforeEach void setup()throws Exception {
        previousConfig=System.getProperty("deposit.config");Path resources=Files.createDirectories(temporary.resolve("resources")),exports=Files.createDirectories(temporary.resolve("exports"));Path config=temporary.resolve("deposit-test.properties");Properties properties=new Properties();properties.setProperty("resourceRoot",resources.toString());properties.setProperty("exportRoot",exports.toString());try(var out=Files.newOutputStream(config)){properties.store(out,"test");}System.setProperty("deposit.config",config.toString());
        management=new DatabaseManagementServiceBuilderImplementation(temporary.resolve("db"),type->type!=DepositExtension.class).build();database=(GraphDatabaseAPI)management.database("neo4j");server=ServerContext.attach(database.databaseId().databaseId().uuid());var field=ServerContext.class.getDeclaredField("database");field.setAccessible(true);field.set(server,database);storage=new StorageManager(server,database);server.storage=storage;
    }
    @AfterEach void close(){if(storage!=null)storage.close();if(management!=null)management.shutdown();if(database!=null)ServerContext.detach(database.databaseId().databaseId().uuid());if(previousConfig==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previousConfig);}

    @Test void sequentialUnequalPublicationsPreserveCapacityForLaterStaging()throws Exception {
        Path first=stage("first.txt","0123456789"),second=stage("second.txt","abcdefg");
        var firstContent=content(first,"first.txt");var secondContent=content(second,"second.txt");
        String scope=UUID.randomUUID().toString(),actor=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();
        var reservation=storage.admission.acquire("import-apply",ImportWriteBudget.reservationOwnerId(scope,actor,operation),operation,"resource-expansion",Map.of(storage.files.root,17L),Json.map("receiptScope",scope,"actorId",actor,"operationId",operation));
        try(var tx=database.beginTx()) {
            var resources=new ResourceRepository(tx,new CoreRepository(tx,ZonedDateTime.now(java.time.ZoneOffset.UTC)));
            storage.admission.materialized(reservation,storage.files.root,10L);
            resources.publish(firstContent,first,reservation);Files.delete(first);
            assertEquals(64L*1024+7L,reservation.commitments().get(storage.admission.volumeId(storage.files.root)));
            storage.admission.materialized(reservation,storage.files.root,7L);
            resources.publish(secondContent,second,reservation);Files.delete(second);
            assertEquals(64L*1024L,reservation.commitments().get(storage.admission.volumeId(storage.files.root)));
            tx.rollback();
        }
        storage.recover();
        assertFalse(Files.exists(storage.files.root.resolve("admission").resolve(reservation.id+".json")),"startup recovery retires the uncommitted reservation after OCFL reconciliation");
        try(var input=storage.content.open(firstContent,0,firstContent.bytes())){assertEquals("0123456789",new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        try(var input=storage.content.open(secondContent,0,secondContent.bytes())){assertEquals("abcdefg",new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        storage.content.assertWorkClean();
    }

    private Path stage(String name,String value)throws Exception{Path path=storage.files.path("staging/imports/"+name);Files.createDirectories(path.getParent());Files.writeString(path,value);return path;}
    private ResourceStorage.Content content(Path path,String name)throws Exception{String checksum=ResourceFiles.checksum(path);return new ResourceStorage.Content(UUID.nameUUIDFromBytes(checksum.getBytes()).toString(),ResourceFiles.filename(name,checksum),Files.size(path),checksum);}
}
