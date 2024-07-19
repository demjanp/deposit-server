package deposit;

import org.junit.After;
import org.junit.Before;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

public abstract class BaseNeo4jTest {

	protected Neo4j embeddedDatabaseServer;
	protected Driver driver;

	@Before
	public void setUp() {
		embeddedDatabaseServer = Neo4jBuilders.newInProcessBuilder()
				.withProcedure(getProcedureClass())
				.build();
		driver = GraphDatabase.driver(embeddedDatabaseServer.boltURI(), AuthTokens.none());
	}

	@After
	public void tearDown() {
		if (driver != null) {
			driver.close();
		}
		embeddedDatabaseServer.close();
	}

	protected abstract Class<?> getProcedureClass();
}
