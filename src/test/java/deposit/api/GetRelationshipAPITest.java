package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.neo4j.driver.types.Relationship;

import java.util.Map;
import java.util.UUID;

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

	private Map<String, String> createObjects(Session session, String startObjectClassName, String endObjectClassName) {
		return session.writeTransaction(tx -> {
			// Create Start Object
			Result startResult = tx.run("CALL deposit.create.object($className, [], [], $token) YIELD node RETURN node.id AS startObjectId",
										Map.of("className", startObjectClassName, "token", token));
			String startObjectId = startResult.single().get("startObjectId").asString();

			// Create End Object
			Result endResult = tx.run("CALL deposit.create.object($className, [], [], $token) YIELD node RETURN node.id AS endObjectId",
									  Map.of("className", endObjectClassName, "token", token));
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
			String startObjectClassName = "ClassA";
			String endObjectClassName = "ClassB";
			String label = "RELATES_TO";
			Double weight = 2.5;

			createNamespace(session, namespaceName, versioningType, token);
			Map<String, String> objectIds = createObjects(session, startObjectClassName, endObjectClassName);
			String startObjectId = objectIds.get("startObjectId");
			String endObjectId = objectIds.get("endObjectId");

			String relationshipId = session.writeTransaction(tx -> {
				Result createResult = tx.run("CALL deposit.create.relationship($startObjectId, $endObjectId, $label, $weight, $token) YIELD rel RETURN rel.id AS relationshipId",
						Map.of("startObjectId", startObjectId,
							   "endObjectId", endObjectId,
							   "label", label,
							   "weight", weight,
							   "token", token));
				return createResult.single().get("relationshipId").asString();
			});

			// Retrieve and consume the result in a single step
			Relationship relationship = session.readTransaction(tx -> {
				Result result = tx.run("CALL deposit.get.relationshipById($relationshipId, $token) YIELD rel RETURN rel",
						Map.of("relationshipId", relationshipId, "token", token));

				// Ensure the result is consumed immediately
				return result.hasNext() ? result.next().get("rel").asRelationship() : null;
			});

			assertNotNull("The relationship should not be null", relationship);
		}
	}


	@Test
	public void shouldReturnNullForNonExistentRelationshipById() {
		try (Session session = driver.session()) {
			String nonExistentRelationshipId = String.valueOf(UUID.randomUUID());

			// Call the get.relationshipById procedure with a non-existent ID
			Result result = session.run("CALL deposit.get.relationshipById($relationshipId, $token) YIELD rel RETURN rel",
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
			String startObjectClassName = "ClassA";
			String endObjectClassName = "ClassB";
			String label = "RELATES_TO";
			Double weight = 2.5;

			createNamespace(session, namespaceName, versioningType, token);
			Map<String, String> objectIds = createObjects(session, startObjectClassName, endObjectClassName);
			String startObjectId = objectIds.get("startObjectId");
			String endObjectId = objectIds.get("endObjectId");

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.relationship($startObjectId, $endObjectId, $label, $weight, $token) YIELD rel RETURN rel",
						Map.of("startObjectId", startObjectId,
							   "endObjectId", endObjectId,
							   "label", label,
							   "weight", weight,
							   "token", token));
				tx.commit();
				return null;
			});

			// Call the get.relationship procedure to retrieve the relationship by label
			Result result = session.run("CALL deposit.get.relationship($startObjectId, $endObjectId, $label, $token) YIELD rel RETURN rel",
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
			String startObjectClassName = "ClassA";
			String endObjectClassName1 = "ClassB";
			String endObjectClassName2 = "ClassC";

			createNamespace(session, namespaceName, versioningType, token);
			Map<String, String> objectIds1 = createObjects(session, startObjectClassName, endObjectClassName1);
			Map<String, String> objectIds2 = createObjects(session, startObjectClassName, endObjectClassName2);
			String startObjectId = objectIds1.get("startObjectId");
			String endObjectId1 = objectIds1.get("endObjectId");
			String endObjectId2 = objectIds2.get("endObjectId");

			String label1 = "RELATES_TO";
			String label2 = "ASSOCIATED_WITH";
			Double weight = 2.5;

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.relationship($startObjectId, $endObjectId, $label, $weight, $token) YIELD rel RETURN rel",
						Map.of("startObjectId", startObjectId,
							   "endObjectId", endObjectId1,
							   "label", label1,
							   "weight", weight,
							   "token", token));
				tx.run("CALL deposit.create.relationship($startObjectId, $endObjectId, $label, $weight, $token) YIELD rel RETURN rel",
						Map.of("startObjectId", startObjectId,
							   "endObjectId", endObjectId2,
							   "label", label2,
							   "weight", weight,
							   "token", token));
				tx.commit();
				return null;
			});

			// Call the get.relationship procedure to retrieve all Relationships for the start object
			Result result = session.run("CALL deposit.get.relationship($startObjectId, '', '', $token) YIELD rel RETURN rel",
					Map.of("startObjectId", startObjectId,
						   "token", token));

			int count = 0;
			while (result.hasNext()) {
				result.next();
				count++;
			}

			assertTrue("Should retrieve both Relationships associated with the start object", count == 2);
		}
	}

	@Test
	public void shouldGetRelationshipWithNoLabelOrEndObjectId() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String versioningType = "transient";
			String startObjectClassName = "ClassA";
			String endObjectClassName = "ClassB";
			String label = "RELATES_TO";
			Double weight = 2.5;

			createNamespace(session, namespaceName, versioningType, token);
			Map<String, String> objectIds = createObjects(session, startObjectClassName, endObjectClassName);
			String startObjectId = objectIds.get("startObjectId");
			String endObjectId = objectIds.get("endObjectId");

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.relationship($startObjectId, $endObjectId, $label, $weight, $token) YIELD rel RETURN rel",
						Map.of("startObjectId", startObjectId,
							   "endObjectId", endObjectId,
							   "label", label,
							   "weight", weight,
							   "token", token));
				tx.commit();
				return null;
			});

			// Call the get.relationship procedure without specifying a label or endObjectId
			Result result = session.run("CALL deposit.get.relationship($startObjectId, '', '', $token) YIELD rel RETURN rel",
					Map.of("startObjectId", startObjectId,
						   "token", token));

			assertTrue("Should retrieve the relationship without needing a label or endObjectId", result.hasNext());
			assertNotNull("The relationship node should not be null", result.next().get("relationship"));
		}
	}
}
