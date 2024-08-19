package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CreateObjectAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	private String generateUniqueName(String baseName) {
		return baseName + "_" + UUID.randomUUID().toString();
	}

	private void createNamespaceAndClass(Session session, String namespaceName, List<String> classNames) {
		session.writeTransaction(tx -> {
			tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
			tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));
			for (String className : classNames) {
				tx.run("CALL deposit.create.class($className, -1, [], '', [], $token)", Map.of("className", className, "token", token));
			}
			tx.commit();
			return null;
		});
	}

	@Test
	public void shouldCreateObjectWithSingleClass() {
		try (Session session = driver.session()) {
			String namespaceName = generateUniqueName("TestNamespace");
			List<String> classes = List.of(generateUniqueName("TestClass"));

			createNamespaceAndClass(session, namespaceName, classes);

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.object($classes, [], [], $token)",
					   Map.of("classes", classes, "token", token));
				tx.commit();
				return null;
			});

			// Verify: Object node was created and has a MEMBER_OF relationship with the specified class
			Result result = session.run("""
				MATCH (o:Object)-[r:MEMBER_OF]->(c:Class {name: $className})
				RETURN o, r, c
				""", Map.of("className", classes.get(0)));

			assertTrue("Object node should exist in the database with a MEMBER_OF relationship to the class", result.hasNext());
			var record = result.next();
			var relationship = record.get("r").asRelationship();
			assertEquals("MEMBER_OF", relationship.type());
			assertEquals(classes.get(0), record.get("c").get("name").asString());
		}
	}

	@Test
	public void shouldCreateObjectWithMultipleClasses() {
		try (Session session = driver.session()) {
			String namespaceName = generateUniqueName("TestNamespace");
			List<String> classes = List.of(generateUniqueName("TestClass1"), generateUniqueName("TestClass2"));

			createNamespaceAndClass(session, namespaceName, classes);

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.object($classes, [], [], $token)",
					   Map.of("classes", classes, "token", token));
				tx.commit();
				return null;
			});

			// Verify: Object node was created and has MEMBER_OF relationships with all specified classes
			Result result = session.run("""
					MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o:Object)-[r:MEMBER_OF]->(c:Class)
					RETURN o, collect(DISTINCT c.name) AS classNames
				""", Map.of("namespaceName", namespaceName));

			assertTrue("Object node should exist in the database with MEMBER_OF relationships to specified classes", result.hasNext());
			List<String> classNames = result.next().get("classNames").asList(v -> v.asString());
			assertEquals(2, classNames.size());
			assertTrue(classNames.containsAll(classes));
		}
	}

	@Test
	public void shouldCreateObjectWithDefaultPosition() {
		try (Session session = driver.session()) {
			String namespaceName = generateUniqueName("TestNamespace");
			List<String> classes = List.of(generateUniqueName("TestClass"));

			createNamespaceAndClass(session, namespaceName, classes);

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.object($classes, [], [], $token)",
					   Map.of("classes", classes, "token", token));
				tx.commit();
				return null;
			});

			// Verify: Object node was created with default position (empty list)
			Result result = session.run("MATCH (o:Object) RETURN o");
			assertTrue("Object node should exist in the database", result.hasNext());
			List<Double> position = result.next().get("o").get("position").asList(Value::asDouble);
			assertTrue("Position should be an empty list", position.isEmpty());
		}
	}

	@Test
	public void shouldCreateObjectWithSpecifiedPosition() {
		try (Session session = driver.session()) {
			String namespaceName = generateUniqueName("TestNamespace");
			List<String> classes = List.of(generateUniqueName("TestClass"));
			List<Double> position = List.of(100.0, 200.0);

			createNamespaceAndClass(session, namespaceName, classes);

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.object($classes, [], $position, $token)",
					   Map.of("classes", classes, "position", position, "token", token));
				tx.commit();
				return null;
			});

			// Verify: Object node was created with specified position
			Result result = session.run("MATCH (o:Object) RETURN o");
			assertTrue("Object node should exist in the database", result.hasNext());
			List<Double> resultPosition = result.next().get("o").get("position").asList(Value::asDouble);
			assertEquals(position, resultPosition);
		}
	}

	@Test
	public void shouldThrowExceptionForEmptyClassesList() {
		try (Session session = driver.session()) {
			String namespaceName = generateUniqueName("TestNamespace");
			createNamespaceAndClass(session, namespaceName, List.of());

			RuntimeException exception = assertThrows(RuntimeException.class, () -> {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.create.object([], [], [], $token)", Map.of("token", token));
					tx.commit();
					return null;
				});
			});

			assertTrue("Expected error message about empty classes list",
					   exception.getMessage().contains("Cannot create Object without specifying at least one Class."));
		}
	}

	@Test
	public void shouldRetrieveObjectById() {
		try (Session session = driver.session()) {
			String namespaceName = generateUniqueName("TestNamespace");
			List<String> classes = List.of(generateUniqueName("TestClass"));

			createNamespaceAndClass(session, namespaceName, classes);

			// Create an object and retrieve its ID
			String objectId = session.writeTransaction(tx -> {
				Result result = tx.run("CALL deposit.create.object($classes, [], [], $token) YIELD node RETURN node.id AS objectId",
						Map.of("classes", classes, "token", token));
				return result.single().get("objectId").asString();
			});

			// Test: Retrieve the object by ID
			session.readTransaction(tx -> {
				Result result = tx.run("CALL deposit.get.objectById($objectId, $token) YIELD node RETURN node",
						Map.of("objectId", objectId, "token", token));

				assertTrue("Object node should be retrieved", result.hasNext());
				Node retrievedNode = result.single().get("node").asNode();
				assertEquals(objectId, retrievedNode.get("id").asString());
				return null;
			});
		}
	}

	@Test
	public void shouldReturnNullForNonExistingObjectId() {
		try (Session session = driver.session()) {
			String nonExistingObjectId = UUID.randomUUID().toString();

			// Test: Attempt to retrieve the object by a non-existing ID
			session.readTransaction(tx -> {
				Result result = tx.run("CALL deposit.get.objectById($objectId, $token) YIELD node RETURN node",
						Map.of("objectId", nonExistingObjectId, "token", token));

				assertFalse("No object node should be retrieved for a non-existing ID", result.hasNext());
				return null;
			});
		}
	}

}
