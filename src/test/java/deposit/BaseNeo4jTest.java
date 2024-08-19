package deposit;

import org.junit.After;
import org.junit.Before;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilder;
import org.neo4j.harness.Neo4jBuilders;
import org.neo4j.configuration.GraphDatabaseSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public abstract class BaseNeo4jTest {

    protected Neo4j embeddedDatabaseServer;
    protected Driver driver;
    private static final String NEO4J_HOME = "c:/documents_synced/Deposit_v2/redist/neo4j/";

    @Before
    public void setUp() {
        // Convert the plugin directory path to a Path object
        Path pluginDirPath = Paths.get(NEO4J_HOME, "plugins").toAbsolutePath();

        // Set up the Neo4jBuilder with necessary configurations
        Neo4jBuilder builder = Neo4jBuilders.newInProcessBuilder()
            .withConfig(GraphDatabaseSettings.plugin_dir, pluginDirPath) // Set plugin directory
            .withConfig(GraphDatabaseSettings.procedure_unrestricted, List.of("apoc.*", "spatial.*", "deposit.*"))
            .withConfig(GraphDatabaseSettings.procedure_allowlist, List.of("apoc.*", "spatial.*", "deposit.*"));

        // Build the embedded database server
        embeddedDatabaseServer = builder.build();

        // Initialize the driver
        driver = GraphDatabase.driver(embeddedDatabaseServer.boltURI(), AuthTokens.none());
    }

    @After
    public void tearDown() {
        if (driver != null) {
            driver.close();
        }
        if (embeddedDatabaseServer != null) {
            embeddedDatabaseServer.close();
        }
    }

}
