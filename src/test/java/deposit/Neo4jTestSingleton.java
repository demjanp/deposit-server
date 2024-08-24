package deposit;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilder;
import org.neo4j.harness.Neo4jBuilders;
import org.neo4j.configuration.GraphDatabaseSettings;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class Neo4jTestSingleton {

	private static Neo4j embeddedDatabaseServer;
	private static Driver driver;
	private static final String NEO4J_HOME = "c:/documents_synced/Deposit_v2/redist/neo4j/";

	private Neo4jTestSingleton() {
		// Private constructor to prevent instantiation
	}

	public static synchronized Driver getDriver() {
		if (driver == null) {
			initializeDatabase();
		}
		return driver;
	}

	private static void initializeDatabase() {
		if (embeddedDatabaseServer == null) {
			Path pluginDirPath = Paths.get(NEO4J_HOME, "plugins").toAbsolutePath();

			Neo4jBuilder builder = Neo4jBuilders.newInProcessBuilder()
				.withConfig(GraphDatabaseSettings.plugin_dir, pluginDirPath)
				.withConfig(GraphDatabaseSettings.procedure_unrestricted, List.of("apoc.*", "spatial.*", "deposit.*"))
				.withConfig(GraphDatabaseSettings.procedure_allowlist, List.of("apoc.*", "spatial.*", "deposit.*"));

			embeddedDatabaseServer = builder.build();
			driver = GraphDatabase.driver(embeddedDatabaseServer.boltURI(), AuthTokens.none());
		}
	}

	public static synchronized void shutdownDatabase() {
		if (driver != null) {
			driver.close();
			driver = null;
		}
		if (embeddedDatabaseServer != null) {
			embeddedDatabaseServer.close();
			embeddedDatabaseServer = null;
		}
	}
}
