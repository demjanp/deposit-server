package deposit.common;

import static org.junit.jupiter.api.Assertions.*;

import deposit.repository.CoreRepository;
import deposit.repository.SchemaRepository;
import deposit.service.AccountService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilderImplementation;
import org.neo4j.internal.kernel.api.connectioninfo.ClientConnectionInfo;
import org.neo4j.internal.kernel.api.security.AuthSubject;
import org.neo4j.internal.kernel.api.security.AuthenticationResult;
import org.neo4j.internal.kernel.api.security.SecurityContext;
import org.neo4j.internal.kernel.api.security.StaticAccessMode;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.monitoring.Monitors;

class OperationalBootstrapTest {
    @TempDir Path directory;

    @Test void bootstrapCommitsInitializationOnlyWithTheFirstAdministrator() throws Exception {
        String previousConfig = System.getProperty("deposit.config");
        var config = directory.resolve("deposit.properties");
        var properties = new Properties();
        var root = Files.createDirectory(directory.resolve("resources"));
        properties.setProperty("resourceRoot", root.toString());
        properties.setProperty("exportRoot", Files.createDirectory(directory.resolve("exports")).toString());
        try (var output = Files.newOutputStream(config)) { properties.store(output, "Operational bootstrap fixture"); }
        System.setProperty("deposit.config", config.toString());
        var management = new DatabaseManagementServiceBuilderImplementation(directory.resolve("database"),
            type -> type != DepositExtension.class).build();
        var database = (GraphDatabaseAPI)management.database("neo4j");
        var databaseId = database.databaseId().databaseId().uuid();
        var server = ServerContext.attach(databaseId);
        var monitors = database.getDependencyResolver().resolveDependency(Monitors.class);
        try {
            server.storage = new StorageManager(server, database);
            SchemaRepository.initialize(database);
            var emptyRoot = Files.createDirectory(directory.resolve("missing-operational-root"));
            var missing = assertThrows(java.io.IOException.class, () -> new OperationalStore(emptyRoot, database, null));
            assertTrue(missing.getMessage().contains("Operational storage is missing"));
            assertFalse(Files.exists(emptyRoot.resolve("operational")));
            var otherManagement = new DatabaseManagementServiceBuilderImplementation(directory.resolve("other-database"),
                type -> type != DepositExtension.class).build();
            try {
                var otherDatabase = (GraphDatabaseAPI)otherManagement.database("neo4j");
                var mismatch = assertThrows(java.io.IOException.class, () -> new OperationalStore(root, otherDatabase, null));
                assertTrue(mismatch.getMessage().contains("belongs to a different Neo4j store"));
            } finally {
                otherManagement.shutdown();
            }
            monitors.addMonitorListener(server);
            management.registerTransactionEventListener("neo4j", server);
            server.markReadyForTesting();
            server.storage.verifyBootstrap();

            assertTrue(Files.isRegularFile(root.resolve("operational/operational.sqlite")));
            var settings = server.storage.operational.read(connection -> {
                var values = new java.util.ArrayList<String>();
                for (String pragma : List.of("journal_mode", "foreign_keys", "synchronous", "busy_timeout")) {
                    try (var statement = connection.createStatement();
                         var rows = statement.executeQuery("PRAGMA " + pragma)) {
                        assertTrue(rows.next());
                        values.add(rows.getString(1));
                    }
                }
                return values;
            });
            assertEquals(List.of("wal", "1", "2", "1000"), settings);

            var login = nativeAdministrator();
            try (var tx = database.beginTransaction(KernelTransaction.Type.EXPLICIT, login)) {
                String user = bootstrap(tx);
                assertEquals(user, server.storage.operationalState.bootstrap().pendingUser());
                assertFalse(server.storage.operationalState.bootstrap().initialized());
            }
            assertNull(server.storage.operationalState.bootstrap().pendingUser());
            assertFalse(server.storage.operationalState.bootstrap().initialized());
            server.storage.verifyBootstrap();

            String committed;
            try (var tx = database.beginTransaction(KernelTransaction.Type.EXPLICIT, login)) {
                committed = bootstrap(tx);
                assertEquals(committed, server.storage.operationalState.bootstrap().pendingUser());
                tx.commit();
            }
            assertTrue(server.storage.operationalState.bootstrap().initialized());
            assertNull(server.storage.operationalState.bootstrap().pendingUser());
            server.storage.verifyBootstrap();
            try (var tx = database.beginTransaction(KernelTransaction.Type.EXPLICIT, login)) {
                var denied = assertThrows(ExceptionHandler.DepositException.class, () -> bootstrap(tx));
                assertTrue(denied.getMessage().contains("Bootstrap unavailable"));
                var core = new CoreRepository(tx, ZonedDateTime.now());
                var user = core.find("User", "id", committed);
                assertEquals("administrator", user.getProperty("serverRole"));
                assertEquals(true, user.getProperty("enabled"));
                assertEquals(1, core.nodes("User", Map.of()).size());
            }
        } finally {
            try {
                server.shutdown();
            } finally {
                monitors.removeMonitorListener(server);
                ServerContext.detach(databaseId);
                management.shutdown();
                if (previousConfig == null) System.clearProperty("deposit.config");
                else System.setProperty("deposit.config", previousConfig);
            }
        }
    }

    private static String bootstrap(org.neo4j.graphdb.Transaction tx) {
        var request = Map.<String,Object>of("username", "first-admin", "password", "test-only-bootstrap-password");
        return (String)new AccountService(new RequestContext(tx, Map.of())).bootstrap(request).data.get("id");
    }

    private static SecurityContext nativeAdministrator() {
        var subject = new AuthSubject() {
            @Override public AuthenticationResult getAuthenticationResult() { return AuthenticationResult.SUCCESS; }
            @Override public boolean hasUsername(String name) { return executingUser().equals(name); }
            @Override public String executingUser() { return "neo4j"; }
        };
        return new SecurityContext(subject, StaticAccessMode.FULL, ClientConnectionInfo.EMBEDDED_CONNECTION, "neo4j");
    }
}
