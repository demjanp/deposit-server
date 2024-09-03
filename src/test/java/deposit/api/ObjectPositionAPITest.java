package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ObjectPositionAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	private String generateUniqueName(String baseName) {
		return baseName + "_" + UUID.randomUUID().toString();
	}

	private void createNamespaceAndClass(Session session, String namespaceName, String className) {
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
	}

	@Test
	public void shouldSetAndRetrieveObjectPosition() {
		try (Session session = driver.session()) {
			String namespaceName = generateUniqueName("TestNamespace");
			String className = generateUniqueName("TestClass");

			createNamespaceAndClass(session, namespaceName, className);

			// Create an object and retrieve its ID
			String objectId = session.writeTransaction(tx -> {
				Result result = tx.run("CALL deposit.create.object($className, [], [], $token) YIELD node RETURN node.id AS objectId",
						Map.of("className", className, "token", token));
				return result.single().get("objectId").asString();
			});

			// Set the position of the object
			List<Double> position = List.of(100.0, 200.0);
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.set.objectPosition($objectId, $position, $token)",
						Map.of("objectId", objectId, "position", position, "token", token));
				tx.commit();
				return null;
			});

			// Retrieve the position of the object and verify
			session.readTransaction(tx -> {
				Result result = tx.run("CALL deposit.get.objectPosition($objectId, $token) YIELD x, y RETURN x, y",
						Map.of("objectId", objectId, "token", token));

				assertTrue("Object position should be retrieved", result.hasNext());
				var record = result.single();
				assertEquals(position.get(0), record.get("x").asDouble(), 0.01);
				assertEquals(position.get(1), record.get("y").asDouble(), 0.01);
				return null;
			});
		}
	}

	@Test
	public void shouldUnsetObjectPosition() {
		try (Session session = driver.session()) {
			String namespaceName = generateUniqueName("TestNamespace");
			String className = generateUniqueName("TestClass");

			createNamespaceAndClass(session, namespaceName, className);

			// Create an object and retrieve its ID
			String objectId = session.writeTransaction(tx -> {
				Result result = tx.run("CALL deposit.create.object($className, [], [], $token) YIELD node RETURN node.id AS objectId",
						Map.of("className", className, "token", token));
				return result.single().get("objectId").asString();
			});

			// Set the position of the object
			List<Double> position = List.of(100.0, 200.0);
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.set.objectPosition($objectId, $position, $token)",
						Map.of("objectId", objectId, "position", position, "token", token));
				tx.commit();
				return null;
			});

			// Unset the position of the object
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.set.objectPosition($objectId, [], $token)",
						Map.of("objectId", objectId, "token", token));
				tx.commit();
				return null;
			});

			// Retrieve the position of the object and verify that it is unset
			session.readTransaction(tx -> {
				Result result = tx.run("CALL deposit.get.objectPosition($objectId, $token) YIELD x, y RETURN x, y",
						Map.of("objectId", objectId, "token", token));

				assertFalse("Object position should be unset", result.hasNext());
				return null;
			});
		}
	}

	@Test
	public void shouldReturnEmptyWhenObjectPositionNotSet() {
		try (Session session = driver.session()) {
			String namespaceName = generateUniqueName("TestNamespace");
			String className = generateUniqueName("TestClass");

			createNamespaceAndClass(session, namespaceName, className);

			// Create an object and retrieve its ID
			String objectId = session.writeTransaction(tx -> {
				Result result = tx.run("CALL deposit.create.object($className, [], [], $token) YIELD node RETURN node.id AS objectId",
						Map.of("className", className, "token", token));
				return result.single().get("objectId").asString();
			});

			// Test: Attempt to retrieve object position when none is set
			session.readTransaction(tx -> {
				Result result = tx.run("CALL deposit.get.objectPosition($objectId, $token) YIELD x, y RETURN x, y",
						Map.of("objectId", objectId, "token", token));

				assertFalse("Object position should not exist", result.hasNext());
				return null;
			});
		}
	}

	@Test
	public void shouldFailToSetPositionForNonExistingObject() {
		try (Session session = driver.session()) {
			String nonExistingObjectId = UUID.randomUUID().toString();
			List<Double> position = List.of(100.0, 200.0);

			// Test: Attempt to set position for a non-existing object
			boolean exceptionThrown = false;
			try {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.set.objectPosition($objectId, $position, $token)",
							Map.of("objectId", nonExistingObjectId, "position", position, "token", token));
					tx.commit();
					return null;
				});
			} catch (Exception e) {
				exceptionThrown = true;
			}

			assertTrue("Setting position for a non-existing object should fail", exceptionThrown);
		}
	}

	@Test
	public void shouldFailToRetrievePositionForNonExistingObject() {
		try (Session session = driver.session()) {
			String nonExistingObjectId = UUID.randomUUID().toString();

			// Test: Attempt to retrieve position for a non-existing object
			session.readTransaction(tx -> {
				Result result = tx.run("CALL deposit.get.objectPosition($objectId, $token) YIELD x, y RETURN x, y",
						Map.of("objectId", nonExistingObjectId, "token", token));

				assertFalse("Retrieving position for a non-existing object should return no result", result.hasNext());
				return null;
			});
		}
	}
}
