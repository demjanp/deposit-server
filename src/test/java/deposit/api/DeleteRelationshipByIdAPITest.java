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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class DeleteRelationshipByIdAPITest extends BaseNeo4jTest {

	private String token;

	@Override
	protected Class<?>[] getProcedureClasses() {
		return new Class<?>[]{DeleteRelationshipByIdAPI.class, CreateRelationshipAPI.class, CreateNamespaceAPI.class, CreateObjectAPI.class, SetActiveNamespaceAPI.class};
	}

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	private Map<String, String> createNamespaceAndRelationship(Session session, String namespaceName, List<String> startObjectClasses, List<String> endObjectClasses) {
		return session.writeTransaction(tx -> {
			// Create and set active Namespace with versioning set to 'transient'
			tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", 
					Map.of("namespaceName", namespaceName, "token", token));
			tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)",
					Map.of("namespaceName", namespaceName, "token", token));
			
			// Create Start Object
			Result startResult = tx.run("CALL deposit.create.object($classes, [], [], $token) YIELD node RETURN node.id AS startObjectId",
										Map.of("classes", startObjectClasses, "token", token));
			String startObjectId = startResult.single().get("startObjectId").asString();

			// Create End Object
			Result endResult = tx.run("CALL deposit.create.object($classes, [], [], $token) YIELD node RETURN node.id AS endObjectId",
									  Map.of("classes", endObjectClasses, "token", token));
			String endObjectId = endResult.single().get("endObjectId").asString();

			// Create Relationship
			Result relResult = tx.run("CALL deposit.create.relationship($startObjectId, $endObjectId, 'RELATES_TO', 1.0, $token) YIELD node RETURN node.id AS relationshipId",
									  Map.of("startObjectId", startObjectId,
											 "endObjectId", endObjectId,
											 "token", token));
			String relationshipId = relResult.single().get("relationshipId").asString();

			tx.commit();
			return Map.of("startObjectId", startObjectId, "endObjectId", endObjectId, "relationshipId", relationshipId);
		});
	}

	@Test
	public void shouldDeleteRelationshipById() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			List<String> startObjectClasses = List.of("ClassA");
			List<String> endObjectClasses = List.of("ClassB");

			Map<String, String> ids = createNamespaceAndRelationship(session, namespaceName, startObjectClasses, endObjectClasses);
			String relationshipId = ids.get("relationshipId");

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.relationshipById($relationshipId, $token)", 
						Map.of("relationshipId", relationshipId, "token", token));
				tx.commit();
				return null;
			});

			// Verify: Relationship node was deleted
			Result result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object)-[:RS]->(rel:Relationship {id: $relationshipId}) RETURN rel",
										Map.of("namespaceName", namespaceName, "relationshipId", relationshipId));

			assertFalse("Relationship node should be deleted", result.hasNext());
		}
	}

	@Test
	public void shouldThrowExceptionWhenDeletingNonExistentRelationship() {
		try (Session session = driver.session()) {
			String nonExistentRelationshipId = String.valueOf(UUID.randomUUID());

			Exception exception = assertThrows(Exception.class, () -> {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.delete.relationshipById($relationshipId, $token)", 
							Map.of("relationshipId", nonExistentRelationshipId, "token", token));
					tx.commit();
					return null;
				});
			});

			String expectedMessage = "Error deleting Relationship " + nonExistentRelationshipId + ". It does not exist.";
			String actualMessage = exception.getMessage();

			assertTrue("Exception message should indicate that the relationship does not exist",
					actualMessage.contains(expectedMessage));
		}
	}
}
