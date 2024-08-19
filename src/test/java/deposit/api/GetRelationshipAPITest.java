package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.neo4j.driver.types.Node;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class GetRelationshipAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	private void createNamespace(Session session, String namespaceName, String versioningType) {
		session.writeTransaction(tx -> {
			tx.run("CALL deposit.create.namespace($namespaceName, '', $versioningType, $token)",
					Map.of("namespaceName", namespaceName, "versioningType", versioningType, "token", token));
			tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)",
					Map.of("namespaceName", namespaceName, "token", token));
			ServerContext.setActiveNamespace(namespaceName, token);
			tx.commit();
			return null;
		});
	}

	private Map<String, String> createObjects(Session session, List<String> startObjectClasses, List<String> endObjectClasses) {
		return session.writeTransaction(tx -> {
			// Create Start Object
			Result startResult = tx.run("CALL deposit.create.object($classes, [], [], $token) YIELD node RETURN node.id AS startObjectId",
										Map.of("classes", startObjectClasses, "token", token));
			String startObjectId = startResult.single().get("startObjectId").asString();

			// Create End Object
			Result endResult = tx.run("CALL deposit.create.object($classes, [], [], $token) YIELD node RETURN node.id AS endObjectId",
									  Map.of("classes", endObjectClasses, "token", token));
			String endObjectId = endResult.single().get("endObjectId").asString();

			tx.commit();

			return Map.of("startObjectId", startObjectId, "endObjectId", endObjectId);
		});
	}

	@Test
	public void shouldGetRelationshipById() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String versioningType = "transient";
			List<String> startObjectClasses = List.of("ClassA");
			List<String> endObjectClasses = List.of("ClassB");
			String label = "RELATES_TO";
			Double weight = 2.5;

			createNamespace(session, namespaceName, versioningType);
			Map<String, String> objectIds = createObjects(session, startObjectClasses, endObjectClasses);
			String startObjectId = objectIds.get("startObjectId");
			String endObjectId = objectIds.get("endObjectId");

			String relationshipId = session.writeTransaction(tx -> {
				Result createResult = tx.run("CALL deposit.create.relationship($startObjectId, $endObjectId, $label, $weight, $token) YIELD node RETURN node.id AS relationshipId",
						Map.of("startObjectId", startObjectId,
							   "endObjectId", endObjectId,
							   "label", label,
							   "weight", weight,
							   "token", token));
				return createResult.single().get("relationshipId").asString();
			});

			// Retrieve and consume the result in a single step
			Node relationshipNode = session.readTransaction(tx -> {
				Result result = tx.run("CALL deposit.get.relationshipById($relationshipId, $token) YIELD source, target, relationship RETURN source, target, relationship",
						Map.of("relationshipId", relationshipId, "token", token));

				// Ensure the result is consumed immediately
				return result.hasNext() ? result.next().get("relationship").asNode() : null;
			});

			assertNotNull("The relationship node should not be null", relationshipNode);
		}
	}


	@Test
	public void shouldReturnNullForNonExistentRelationshipById() {
		try (Session session = driver.session()) {
			String nonExistentRelationshipId = "non-existent-id";

			// Call the get.relationshipById procedure with a non-existent ID
			Result result = session.run("CALL deposit.get.relationshipById($relationshipId, $token) YIELD source, target, relationship RETURN source, target, relationship",
					Map.of("relationshipId", nonExistentRelationshipId,
						   "token", token));

			assertFalse("Should not retrieve any relationship", result.hasNext());
		}
	}

	@Test
	public void shouldGetRelationshipByLabel() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String versioningType = "transient";
			List<String> startObjectClasses = List.of("ClassA");
			List<String> endObjectClasses = List.of("ClassB");
			String label = "RELATES_TO";
			Double weight = 2.5;

			createNamespace(session, namespaceName, versioningType);
			Map<String, String> objectIds = createObjects(session, startObjectClasses, endObjectClasses);
			String startObjectId = objectIds.get("startObjectId");
			String endObjectId = objectIds.get("endObjectId");

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.relationship($startObjectId, $endObjectId, $label, $weight, $token) YIELD node RETURN node",
						Map.of("startObjectId", startObjectId,
							   "endObjectId", endObjectId,
							   "label", label,
							   "weight", weight,
							   "token", token));
				tx.commit();
				return null;
			});

			// Call the get.relationship procedure to retrieve the relationship by label
			Result result = session.run("CALL deposit.get.relationship($startObjectId, $endObjectId, $label, $token) YIELD source, target, relationship RETURN source, target, relationship",
					Map.of("startObjectId", startObjectId,
						   "endObjectId", endObjectId,
						   "label", label,
						   "token", token));

			assertTrue("Should retrieve the relationship with the specified label", result.hasNext());
			assertNotNull("The relationship node should not be null", result.next().get("relationship"));
		}
	}

	@Test
	public void shouldGetAllRelationshipsForObject() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String versioningType = "transient";
			List<String> startObjectClasses = List.of("ClassA");
			List<String> endObjectClasses1 = List.of("ClassB");
			List<String> endObjectClasses2 = List.of("ClassC");

			createNamespace(session, namespaceName, versioningType);
			Map<String, String> objectIds1 = createObjects(session, startObjectClasses, endObjectClasses1);
			Map<String, String> objectIds2 = createObjects(session, startObjectClasses, endObjectClasses2);
			String startObjectId = objectIds1.get("startObjectId");
			String endObjectId1 = objectIds1.get("endObjectId");
			String endObjectId2 = objectIds2.get("endObjectId");

			String label1 = "RELATES_TO";
			String label2 = "ASSOCIATED_WITH";
			Double weight = 2.5;

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.relationship($startObjectId, $endObjectId, $label, $weight, $token) YIELD node RETURN node",
						Map.of("startObjectId", startObjectId,
							   "endObjectId", endObjectId1,
							   "label", label1,
							   "weight", weight,
							   "token", token));
				tx.run("CALL deposit.create.relationship($startObjectId, $endObjectId, $label, $weight, $token) YIELD node RETURN node",
						Map.of("startObjectId", startObjectId,
							   "endObjectId", endObjectId2,
							   "label", label2,
							   "weight", weight,
							   "token", token));
				tx.commit();
				return null;
			});

			// Call the get.relationship procedure to retrieve all relationships for the start object
			Result result = session.run("CALL deposit.get.relationship($startObjectId, '', '', $token) YIELD source, target, relationship RETURN source, target, relationship",
					Map.of("startObjectId", startObjectId,
						   "token", token));

			int count = 0;
			while (result.hasNext()) {
				result.next();
				count++;
			}

			assertTrue("Should retrieve both relationships associated with the start object", count == 2);
		}
	}

	@Test
	public void shouldGetRelationshipWithNoLabelOrEndObjectId() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String versioningType = "transient";
			List<String> startObjectClasses = List.of("ClassA");
			List<String> endObjectClasses = List.of("ClassB");
			String label = "RELATES_TO";
			Double weight = 2.5;

			createNamespace(session, namespaceName, versioningType);
			Map<String, String> objectIds = createObjects(session, startObjectClasses, endObjectClasses);
			String startObjectId = objectIds.get("startObjectId");
			String endObjectId = objectIds.get("endObjectId");

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.relationship($startObjectId, $endObjectId, $label, $weight, $token) YIELD node RETURN node",
						Map.of("startObjectId", startObjectId,
							   "endObjectId", endObjectId,
							   "label", label,
							   "weight", weight,
							   "token", token));
				tx.commit();
				return null;
			});

			// Call the get.relationship procedure without specifying a label or endObjectId
			Result result = session.run("CALL deposit.get.relationship($startObjectId, '', '', $token) YIELD source, target, relationship RETURN source, target, relationship",
					Map.of("startObjectId", startObjectId,
						   "token", token));

			assertTrue("Should retrieve the relationship without needing a label or endObjectId", result.hasNext());
			assertNotNull("The relationship node should not be null", result.next().get("relationship"));
		}
	}
}
