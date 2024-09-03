package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.neo4j.driver.Value;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

public class ClassPositionAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	@Test
	public void shouldVerifyPositionPropertyOnClassNode() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace and a Class
			String namespaceName = "TestNamespace";
			String className = "TestClass";
			List<Double> position = List.of(100.0, 200.0);

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)",
					Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)",
					Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.create.class($className, -1, [], '', [], $token)",
					Map.of("className", className, "token", token));
				tx.commit();
				return null;
			});

			// Test: Set class position
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.set.classPosition($className, $position, $token)",
					Map.of("className", className, "position", position, "token", token));
				tx.commit();
				return null;
			});

			// Verify: Check if the position property is correctly set on the Class node
			Result result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: $className}) RETURN c.position AS position",
				Map.of("namespaceName", namespaceName,"className", className));

			assertTrue(result.hasNext());
			List<Double> retrievedPosition = result.next().get("position").asList(Value::asDouble);
			assertEquals(position, retrievedPosition);
		}
	}

	@Test
	public void shouldSetAndRetrieveClassPosition() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace and a Class
			String namespaceName = "TestNamespace";
			String className = "TestClass";
			List<Double> position = List.of(100.0, 200.0);

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", 
					Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", 
					Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.create.class($className, -1, [], '', [], $token)", 
					Map.of("className", className, "token", token));
				tx.commit();
				return null;
			});

			// Test: Set class position
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.set.classPosition($className, $position, $token)", 
					Map.of("className", className, "position", position, "token", token));
				tx.commit();
				return null;
			});

			// Verify: Retrieve class position
			Result result = session.run("CALL deposit.get.classPosition($className, $token) YIELD x, y RETURN x, y", 
				Map.of("className", className, "token", token));

			assertTrue(result.hasNext());
			var record = result.next();
			assertEquals(position.get(0), record.get("x").asDouble(), 0.01);
			assertEquals(position.get(1), record.get("y").asDouble(), 0.01);
		}
	}

	@Test
	public void shouldReturnEmptyWhenClassPositionNotSet() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace and a Class without setting a position
			String namespaceName = "TestNamespace";
			String className = "TestClass";

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", 
					Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", 
					Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.create.class($className, -1, [], '', [], $token)", 
					Map.of("className", className, "token", token));
				tx.commit();
				return null;
			});

			// Test: Attempt to retrieve class position when none is set
			Result result = session.run("CALL deposit.get.classPosition($className, $token) YIELD x, y RETURN x, y", 
				Map.of("className", className, "token", token));

			assertFalse(result.hasNext());
		}
	}

	@Test
	public void shouldFailToSetPositionForNonExistingClass() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace without a Class
			String namespaceName = "TestNamespace";
			String nonExistingClassName = "NonExistingClass";
			List<Double> position = List.of(100.0, 200.0);

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", 
					Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", 
					Map.of("namespaceName", namespaceName, "token", token));
				tx.commit();
				return null;
			});

			// Test: Attempt to set position for a non-existing class
			boolean exceptionThrown = false;
			try {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.set.classPosition($className, $position, $token)", 
						Map.of("className", nonExistingClassName, "position", position, "token", token));
					tx.commit();
					return null;
				});
			} catch (Exception e) {
				exceptionThrown = true;
			}

			assertTrue(exceptionThrown);
		}
	}

	@Test
	public void shouldFailToRetrievePositionForNonExistingClass() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace without a Class
			String namespaceName = "TestNamespace";
			String nonExistingClassName = "NonExistingClass";

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", 
					Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", 
					Map.of("namespaceName", namespaceName, "token", token));
				tx.commit();
				return null;
			});

			// Test: Attempt to retrieve position for a non-existing class
			Result result = session.run("CALL deposit.get.classPosition($className, $token) YIELD x, y RETURN x, y", 
				Map.of("className", nonExistingClassName, "token", token));

			// The result should be empty since the class does not exist
			assertFalse(result.hasNext());
		}
	}
}
