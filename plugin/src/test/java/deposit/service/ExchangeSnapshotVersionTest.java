package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.*;
import deposit.repository.CoreRepository;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.*;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

class ExchangeSnapshotVersionTest {
    @TempDir Path temporary;
    DatabaseManagementService management;
    GraphDatabaseAPI database;
    String previousConfig;

    @BeforeEach void setup() throws Exception {
        previousConfig=System.getProperty("deposit.config");
        Path resources=temporary.resolve("resources"),exports=temporary.resolve("exports");
        java.nio.file.Files.createDirectories(resources);java.nio.file.Files.createDirectories(exports);
        Path config=temporary.resolve("deposit-test.properties");var properties=new Properties();
        properties.setProperty("resourceRoot",resources.toString());properties.setProperty("exportRoot",exports.toString());
        try(var out=java.nio.file.Files.newOutputStream(config)){properties.store(out,"test");}
        System.setProperty("deposit.config",config.toString());
        management=new DatabaseManagementServiceBuilderImplementation(temporary.resolve("db"),type->type!=DepositExtension.class).build();
        database=(GraphDatabaseAPI)management.database("neo4j");
        ServerContext server=ServerContext.attach(database.databaseId().databaseId().uuid());
        var databaseField=ServerContext.class.getDeclaredField("database");databaseField.setAccessible(true);databaseField.set(server,database);
        server.storage=new StorageManager(server,database);
        var ready=ServerContext.class.getDeclaredMethod("markReadyForTesting");ready.setAccessible(true);ready.invoke(server);
    }

    @AfterEach void close() {
        if(database!=null){ServerContext server=ServerContext.forDatabase(database.databaseId().databaseId().uuid());if(server!=null&&server.storage!=null)server.storage.close();}
        if(management!=null)management.shutdown();
        if(database!=null)ServerContext.detach(database.databaseId().databaseId().uuid());
        if(previousConfig==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previousConfig);
    }

    @Test void exportManifestUsesCurrentServerBuildVersionInsteadOfNamespaceCreationVersion() throws Exception {
        String actor=UUID.randomUUID().toString();
        try(Transaction tx=database.beginTx()) {
            var repo=new CoreRepository(tx,ZonedDateTime.now(ZoneOffset.UTC));
            var ns=repo.create("Namespace",null,actor,Json.map("name","Exchange version test","description","","sequence",0L,"historyMode","disabled","historyEpochId",UUID.randomUUID().toString(),"depositVersion","1.2.3"));
            String namespace=(String)ns.getProperty("id");
            var context=new RequestContext(tx,Map.of());context.namespaceId=namespace;
            try(var records=new ExportRecords(temporary.resolve("exports"))) {
                var snapshot=new ExchangeSnapshot(context,repo,records).snapshot(true);
                var manifest=ValueCodec.map(snapshot.get("manifest"));
                assertEquals(DepositInfo.version(),manifest.get("depositVersion"));
                assertNotEquals("1.2.3",manifest.get("depositVersion"));
            }
            tx.rollback();
        }
    }
}
