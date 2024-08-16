package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;

import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class DeleteClassByIdAPITest extends BaseNeo4jTest {

	private String token;

	@Override
	protected Class<?>[] getProcedureClasses() {
		return new Class<?>[]{
			DeleteClassByIdAPI.class,
			DeleteClassAPI.class,
			CreateClassAPI.class,
			CreateNamespaceAPI.class,
			SetActiveNamespaceAPI.class,
			CreateClassRelationshipAPI.class,
			CreateClassDescriptorAPI.class
		};
	}

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	private void createNamespaceAndClasses(Session session, String namespaceName, String startClassName, String endClassName) {
		session.writeTransaction(tx -> {
			// Create the Namespace
			tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)",
				   Map.of("namespaceName", namespaceName, "token", token));
			tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)",
				   Map.of("namespaceName", namespaceName, "token", token));

			// Create the Start Class
			tx.run("CALL deposit.create.class($className, -1, [], '', [], $token) YIELD node RETURN node.id AS startClassId",
				   Map.of("className", startClassName, "token", token));

			// Create the End Class
			tx.run("CALL deposit.create.class($className, -1, [], '', [], $token) YIELD node RETURN node.id AS endClassId",
				   Map.of("className", endClassName, "token", token));

			tx.commit();
			return null;
		});
	}

	@Test
	public void shouldDeleteClassById() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String className = "TestClass";

			// Create namespace and class
			createNamespaceAndClasses(session, namespaceName, className, "DummyClass");

			// Get the ID of the class
			String classId = session.readTransaction(tx -> {
				Result result = tx.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: $className}) RETURN c.id AS id",
									   Map.of("namespaceName", namespaceName, "className", className));
				return result.single().get("id").asString();
			});

			// Delete the class by ID
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.classById($classId, $token)",
					   Map.of("classId", classId, "token", token));
				tx.commit();
				return null;
			});

			// Verify the class node has been deleted within the namespace
			Result result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {id: $classId}) RETURN c",
										Map.of("namespaceName", namespaceName, "classId", classId));
			assertFalse("Class node should be deleted", result.hasNext());
		}
	}
	
	@Test
	public void shouldDeleteClassByName() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String className = "TestClass";

			// Create namespace and class
			createNamespaceAndClasses(session, namespaceName, className, "DummyClass");

			// Delete the class
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.class($className, $token)",
					   Map.of("className", className, "token", token));
				tx.commit();
				return null;
			});

			// Verify the class node has been deleted within the namespace
			Result result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: $className}) RETURN c",
										Map.of("namespaceName", namespaceName, "className", className));
			assertFalse("Class node should be deleted", result.hasNext());
		}
	}

	@Test
	public void shouldDeleteClassWithRelationshipsAndDescriptors() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String startClassName = "StartClass";
			String endClassName = "EndClass";

			// Create namespace and classes
			createNamespaceAndClasses(session, namespaceName, startClassName, endClassName);

			// Create a relationship between the start and end classes
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.classRelationship($startClassName, $endClassName, 'RELATES_TO', 'Test relationship', $token) YIELD node RETURN node",
					   Map.of("startClassName", startClassName,
							  "endClassName", endClassName,
							  "token", token));
				tx.commit();
				return null;
			});

			// Get the ID of the start class
			String classId = session.readTransaction(tx -> {
				Result result = tx.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: $startClassName}) RETURN c.id AS id",
									   Map.of("namespaceName", namespaceName, "startClassName", startClassName));
				return result.single().get("id").asString();
			});

			// Create a descriptor for the start class
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.classDescriptor($startClassName, 'TestDescriptor', 'String', 1, 'Test descriptor', $token)",
					   Map.of("startClassName", startClassName, "token", token));
				tx.commit();
				return null;
			});

			// Delete the start class by ID
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.classById($classId, $token)",
					   Map.of("classId", classId, "token", token));
				tx.commit();
				return null;
			});

			// Verify the start class node has been deleted within the namespace
			Result resultClass = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {id: $classId}) RETURN c",
											 Map.of("namespaceName", namespaceName, "classId", classId));
			assertFalse("Class node should be deleted", resultClass.hasNext());

			// Verify the class relationship has been deleted
			Result resultRelationship = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: $startClassName})-[:RS]->(rel:Relationship) RETURN rel",
													Map.of("namespaceName", namespaceName, "startClassName", startClassName));
			assertFalse("Class relationship should be deleted", resultRelationship.hasNext());

			// Verify the class descriptor has been deleted
			Result resultDescriptor = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class)-[:HAS_DESCRIPTOR]->(d:Descriptor {name: 'TestDescriptor'}) RETURN d",
												  Map.of("namespaceName", namespaceName));
			assertFalse("Class descriptor should be deleted", resultDescriptor.hasNext());
		}
	}

	@Test
	public void shouldThrowExceptionWhenDeletingNonExistentClass() {
		try (Session session = driver.session()) {
			String nonExistentClassId = String.valueOf(UUID.randomUUID());

			Exception exception = assertThrows(Exception.class, () -> {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.delete.classById($classId, $token)",
						   Map.of("classId", nonExistentClassId, "token", token));
					tx.commit();
					return null;
				});
			});

			String expectedMessage = "Error deleting the Class " + nonExistentClassId + ". It does not exist.";
			String actualMessage = exception.getMessage();

			assertTrue("Exception message should indicate that the class does not exist",
					   actualMessage.contains(expectedMessage));
		}
	}
}
