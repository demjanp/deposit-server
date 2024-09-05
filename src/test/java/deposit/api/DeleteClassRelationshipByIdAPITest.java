package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class DeleteClassRelationshipByIdAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	private void createNamespaceAndClasses(Session session, String namespaceName, String startClassName, String endClassName) {
		session.writeTransaction(tx -> {
			// Create the Namespace
			tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
			tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));

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

	private String createClassRelationship(Session session, String startClassName, String endClassName, String label, String comment) {
		return session.writeTransaction(tx -> {
			Result result = tx.run("CALL deposit.create.classRelationship($startClassName, $endClassName, $label, $comment, $token) YIELD rel RETURN rel.id AS relationshipId",
					Map.of("startClassName", startClassName,
							"endClassName", endClassName,
							"label", label,
							"comment", comment,
							"token", token));
			String relationshipId = result.single().get("relationshipId").asString();
			tx.commit();
			return relationshipId;
		});
	}

	@Test
	public void shouldDeleteClassRelationshipById() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String startClassName = "StartClass";
			String endClassName = "EndClass";
			String label = "RELATES_TO";
			String comment = "This is a test relationship.";

			createNamespaceAndClasses(session, namespaceName, startClassName, endClassName);
			String relationshipId = createClassRelationship(session, startClassName, endClassName, label, comment);

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.classRelationshipById($relationshipId, $token)",
						Map.of("relationshipId", relationshipId, "token", token));
				tx.commit();
				return null;
			});

			// Verify: Class Relationship node was deleted
			Result result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class)-[r:RELATED_TO {id: $relationshipId}]->() RETURN r",
										Map.of("namespaceName", namespaceName,
												"relationshipId", relationshipId));

			assertFalse("Class Relationship node should be deleted", result.hasNext());
		}
	}

	@Test
	public void shouldDeleteClassRelationshipByName() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String startClassName = "StartClass";
			String endClassName = "EndClass";
			String label = "RELATES_TO";
			String comment = "This is a test relationship.";

			// Step 1: Create Namespace, Classes, and Class Relationship
			createNamespaceAndClasses(session, namespaceName, startClassName, endClassName);
			createClassRelationship(session, startClassName, endClassName, label, comment);

			// Step 2: Create Objects for Start and End Classes
			session.writeTransaction(tx -> {
				// Create Start Object
				Result startResult = tx.run("CALL deposit.create.object($startClassName, [], [], $token) YIELD node RETURN node.id AS startObjectId",
											Map.of("startClassName", startClassName, "token", token));
				String startObjectId = startResult.single().get("startObjectId").asString();

				// Create End Object
				Result endResult = tx.run("CALL deposit.create.object($endClassName, [], [], $token) YIELD node RETURN node.id AS endObjectId",
										  Map.of("endClassName", endClassName, "token", token));
				String endObjectId = endResult.single().get("endObjectId").asString();

				// Step 3: Create Relationship between Objects
				tx.run("CALL deposit.create.relationship($startObjectId, $endObjectId, $label, 1.0, $token) YIELD rel RETURN rel",
					   Map.of("startObjectId", startObjectId, "endObjectId", endObjectId, "label", label, "token", token));

				tx.commit();
				return null;
			});

			// Step 4: Delete the Class Relationship by Name
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.classRelationship($startClassName, $endClassName, $label, $token)",
						Map.of("startClassName", startClassName, "endClassName", endClassName, "label", label, "token", token));
				tx.commit();
				return null;
			});

			// Step 5: Verify that the Class Relationship and Object Relationships are deleted
			Result result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class {name: $startClassName})-[r:RELATED_TO {label: $label}]->(:Class {name: $endClassName}) RETURN r",
										Map.of("namespaceName", namespaceName, "startClassName", startClassName, "endClassName", endClassName, "label", label));

			assertFalse("Class Relationship node should be deleted", result.hasNext());

			Result objectRelResult = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(oStart:Object)-[r:RELATED_TO {label: $label}]->(oEnd:Object) RETURN r",
										Map.of("namespaceName", namespaceName, "label", label));

			assertFalse("Object Relationship node should be deleted", objectRelResult.hasNext());
		}
	}

	@Test
	public void shouldThrowExceptionWhenDeletingNonExistentClassRelationship() {
		try (Session session = driver.session()) {
			String nonExistentRelationshipId = String.valueOf(UUID.randomUUID());

			Exception exception = assertThrows(Exception.class, () -> {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.delete.classRelationshipById($relationshipId, $token)",
							Map.of("relationshipId", nonExistentRelationshipId, "token", token));
					tx.commit();
					return null;
				});
			});

			String expectedMessage = "Error deleting Class Relationship " + nonExistentRelationshipId + ". It does not exist.";
			String actualMessage = exception.getMessage();

			assertTrue("Exception message should indicate that the class relationship does not exist",
					actualMessage.contains(expectedMessage));
		}
	}
}
