package deposit.api;

import deposit.BaseNeo4jTest;
import org.junit.Test;
import org.neo4j.driver.Session;

import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class CreateNamespaceAPITest extends BaseNeo4jTest {

	@Test
	public void shouldCreateNamespaceNode() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String description = "This is a test namespace.";
			String versioningType = "persistent";

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, $description, $versioningType, '')",
						Map.of("namespaceName", namespaceName, "description", description, "versioningType", versioningType));
				tx.commit();
				return null;
			});

			var result = session.run("MATCH (n:Namespace {name: $namespaceName}) RETURN n", Map.of("namespaceName", namespaceName));
			assertTrue(result.hasNext());
		}
	}

	@Test
	public void shouldThrowExceptionIfNamespaceAlreadyExists() {
		try (Session session = driver.session()) {
			String namespaceName = "DuplicateNamespace";

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', '', '')", Map.of("namespaceName", namespaceName));
				tx.commit();
				return null;
			});

			// Try to create the same namespace again, which should throw an exception
			try {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.create.namespace($namespaceName, '', '', '')", Map.of("namespaceName", namespaceName));
					tx.commit();
					return null;
				});
				fail("Expected RuntimeException not thrown");
			} catch (RuntimeException e) {
				assertTrue(e.getMessage().contains("Error creating Namespace. A namespace with the name: " + namespaceName + " already exists."));
			}
		}
	}

	@Test
	public void shouldCreateNamespaceNodeWithDefaultValues() {
		try (Session session = driver.session()) {
			String namespaceName = "DefaultNamespace";

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', '', '')", Map.of("namespaceName", namespaceName));
				tx.commit();
				return null;
			});

			var result = session.run("MATCH (n:Namespace {name: $namespaceName}) RETURN n.description as description, n.versioningType as versioningType", Map.of("namespaceName", namespaceName));
			assertTrue(result.hasNext());
			var record = result.next();
			assertEquals("", record.get("description").asString());
			assertEquals("", record.get("versioningType").asString());
		}
	}
}
