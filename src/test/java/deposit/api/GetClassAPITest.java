package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;

import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

public class GetClassAPITest extends BaseNeo4jTest {

	private String token;

	@Override
	protected Class<?>[] getProcedureClasses() {
		return new Class<?>[]{GetClassAPI.class, CreateNamespaceAPI.class, CreateClassAPI.class, SetActiveNamespaceAPI.class};
	}

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	@Test
	public void shouldRetrieveSpecificClassNode() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace and two Classes
			String namespaceName = "TestNamespace";
			String className1 = "TestClass1";
			String className2 = "TestClass2";

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.create.class($className, -1, [], '', [], $token)", Map.of("className", className1, "token", token));
				tx.run("CALL deposit.create.class($className, -1, [], '', [], $token)", Map.of("className", className2, "token", token));
				tx.commit();
				return null;
			});

			// Test: Retrieve specific class node
			Result result = session.run("CALL deposit.get.class($className, $token) YIELD node RETURN node",
					Map.of("className", className1, "token", token));

			assertTrue(result.hasNext());
			assertEquals(className1, result.next().get("node").asNode().get("name").asString());
		}
	}

	@Test
	public void shouldRetrieveAllClassNodes() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace and two Classes
			String namespaceName = "TestNamespace";
			String className1 = "TestClass1";
			String className2 = "TestClass2";

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.create.class($className, -1, [], '', [], $token)", Map.of("className", className1, "token", token));
				tx.run("CALL deposit.create.class($className, -1, [], '', [], $token)", Map.of("className", className2, "token", token));
				tx.commit();
				return null;
			});

			// Test: Retrieve all class nodes
			Result result = session.run("CALL deposit.get.class('', $token) YIELD node RETURN node.name AS className",
					Map.of("token", token));

			assertTrue(result.hasNext());
			var classes = result.list(record -> record.get("className").asString());
			assertTrue(classes.contains(className1));
			assertTrue(classes.contains(className2));
		}
	}

	@Test
	public void shouldReturnEmptyWhenClassDoesNotExist() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace but no Classes
			String namespaceName = "EmptyNamespace";

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.commit();
				return null;
			});

			// Test: Try to retrieve a non-existing class
			Result result = session.run("CALL deposit.get.class('NonExistingClass', $token) YIELD node RETURN node",
					Map.of("token", token));

			assertFalse(result.hasNext());
		}
	}
}
