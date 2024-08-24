package deposit;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.After;
import org.junit.BeforeClass;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;

public abstract class BaseNeo4jTest {

	protected static Driver driver;

	@BeforeClass
	public static void setUpOnce() {
		driver = Neo4jTestSingleton.getDriver();
	}

	@AfterClass
	public static void tearDownOnce() {
		// Optionally, if you want to shut down the database after all suites
		// Neo4jTestSingleton.shutdownDatabase();
	}

	@Before
	public void setUp() {
		// Clear the database before each test
		try (Session session = driver.session();
			 Transaction tx = session.beginTransaction()) {
			tx.run("MATCH (n) DETACH DELETE n");
			tx.commit();
		}
	}

	@After
	public void tearDown() {
		// Any per-test cleanup can go here, if necessary
	}
}
