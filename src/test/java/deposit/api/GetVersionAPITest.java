package deposit.api;

import deposit.BaseNeo4jTest;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.neo4j.driver.Record;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

public class GetVersionAPITest extends BaseNeo4jTest {

	@Test
	public void shouldReturnVersionNumber() {
		try (Session session = driver.session()) {
			session.readTransaction(tx -> {
				Result result = tx.run("CALL deposit.get.version() YIELD value AS version");
				
				assertTrue("Version value should be returned", result.hasNext());
				
				// Get the returned version value
				Record record = result.next();
				String version = record.get("version").asString();
				
				System.out.println("VER:" + version); // DEBUG

				assertNotNull("Version should not be null", version);
				assertTrue("Version should not be empty", !version.isEmpty());
				assertTrue("Version should match the expected format", version.matches("\\d+\\.\\d+\\.\\d+(-[\\w\\d.-]+)?"));

				tx.commit();
				return null;
			});
		}
	}
}
