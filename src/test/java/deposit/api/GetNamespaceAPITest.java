package deposit.api;

import deposit.BaseNeo4jTest;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;

import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

public class GetNamespaceAPITest extends BaseNeo4jTest {

	@Override
	protected Class<?>[] getProcedureClasses() {
		return new Class<?>[]{GetNamespaceAPI.class, CreateNamespaceAPI.class};
	}

	@Test
	public void shouldRetrieveSpecificNamespaceNode() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String description = "This is a test namespace.";
			String versioningType = "persistent";

			// Setup: Create a Namespace
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, $description, $versioningType, '')",
						Map.of("namespaceName", namespaceName, "description", description, "versioningType", versioningType));
				tx.commit();
				return null;
			});

			// Test: Retrieve specific namespace node
			Result result = session.run("CALL deposit.get.namespace($namespaceName, '') YIELD node RETURN node",
					Map.of("namespaceName", namespaceName));

			assertTrue(result.hasNext());
			assertEquals(namespaceName, result.next().get("node").asNode().get("name").asString());
		}
	}

	@Test
	public void shouldRetrieveAllNamespaceNodes() {
		try (Session session = driver.session()) {
			String namespaceName1 = "Namespace1";
			String namespaceName2 = "Namespace2";

			// Setup: Create two Namespaces
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', '', '')", Map.of("namespaceName", namespaceName1));
				tx.run("CALL deposit.create.namespace($namespaceName, '', '', '')", Map.of("namespaceName", namespaceName2));
				tx.commit();
				return null;
			});

			// Test: Retrieve all namespace nodes
			Result result = session.run("CALL deposit.get.namespace('', '') YIELD node RETURN node.name AS namespaceName");

			assertTrue(result.hasNext());
			var namespaces = result.list(record -> record.get("namespaceName").asString());
			assertTrue(namespaces.contains(namespaceName1));
			assertTrue(namespaces.contains(namespaceName2));
		}
	}

	@Test
	public void shouldReturnEmptyWhenNamespaceDoesNotExist() {
		try (Session session = driver.session()) {
			String nonExistentNamespace = "NonExistentNamespace";

			// Test: Try to retrieve a non-existing namespace
			Result result = session.run("CALL deposit.get.namespace($namespaceName, '') YIELD node RETURN node",
					Map.of("namespaceName", nonExistentNamespace));

			assertFalse(result.hasNext());
		}
	}
}
