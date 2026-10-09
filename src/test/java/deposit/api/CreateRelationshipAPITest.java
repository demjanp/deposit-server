package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CreateRelationshipAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	private Map<String, String> createNamespaceAndObjects(Session session, String namespaceName, String startObjectClassName, String endObjectClassName, String versioningType) {
		return session.writeTransaction(tx -> {
			// Create and set active Namespace with versioning type
			tx.run("CALL deposit.create.namespace($namespaceName, '', $versioningType, $token)",
					Map.of("namespaceName", namespaceName, "versioningType", versioningType, "token", token));
			tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)",
					Map.of("namespaceName", namespaceName, "token", token));
			ServerContext.setActiveNamespace(namespaceName, token);

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
	public void shouldCreateRelationship() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String versioningType = "transient";  // Set versioning type to 'transient'
			String startObjectClassName = "ClassA";
			String endObjectClassName = "ClassB";
			String label = "RELATES_TO";
			Double weight = 2.5;

			Map<String, String> objectIds = createNamespaceAndObjects(session, namespaceName, startObjectClassName, endObjectClassName, versioningType);
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

			// Verify: Relationship node was created
			Result resultObject = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object {id: $startObjectId})-[rel:RELATED_TO {label: $label}]->(:Object {id: $endObjectId}) RETURN rel",
					Map.of("namespaceName", namespaceName,
						   "startObjectId", startObjectId,
						   "endObjectId", endObjectId,
						   "label", label));

			assertTrue("Relationship node should exist with correct label", resultObject.hasNext());
			assertNotNull("The relationship node should not be null", resultObject.next().get("rel"));

			// Verify: Class Relationship node was created
			Result resultClass = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class {name: $startClassName})-[rel:RELATED_TO {label: $label}]->(:Class {name: $endClassName}) RETURN rel",
					Map.of("namespaceName", namespaceName,
						   "startClassName", startObjectClassName,
						   "endClassName", endObjectClassName,
						   "label", label));

			assertTrue("Class Relationship node should exist with correct label", resultClass.hasNext());
			assertNotNull("The relationship node should not be null", resultClass.next().get("rel"));

		}
	}

	@Test(expected = Exception.class)
	public void shouldNotCreateDuplicateRelationship() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String versioningType = "transient";  // Set versioning type to 'transient'
			String startObjectClassName = "ClassA";
			String endObjectClassName = "ClassB";
			String label = "RELATES_TO";
			Double weight = 2.5;

			Map<String, String> objectIds = createNamespaceAndObjects(session, namespaceName, startObjectClassName, endObjectClassName, versioningType);
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

			// Attempt to create the same relationship again, should throw an exception
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
		}
	}

	@Test
	public void shouldCreateRelationshipWithNullWeight() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String versioningType = "transient";  // Set versioning type to 'transient'
			String startObjectClassName = "ClassA";
			String endObjectClassName = "ClassB";
			String label = "RELATES_TO";
			Double weight = null;  // Weight is null

			Map<String, String> objectIds = createNamespaceAndObjects(session, namespaceName, startObjectClassName, endObjectClassName, versioningType);
			String startObjectId = objectIds.get("startObjectId");
			String endObjectId = objectIds.get("endObjectId");

			session.writeTransaction(tx -> {
				// Create the map conditionally to handle the null weight
				Map<String, Object> params = new HashMap<>();
				params.put("startObjectId", startObjectId);
				params.put("endObjectId", endObjectId);
				params.put("label", label);
				params.put("weight", weight);  // weight is null here
				params.put("token", token);

				tx.run("CALL deposit.create.relationship($startObjectId, $endObjectId, $label, $weight, $token) YIELD rel RETURN rel", params);
				tx.commit();
				return null;
			});

			// Verify: Relationship node was created
			Result result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(oStart:Object {id: $startObjectId})-[rel:RELATED_TO {label: $label}]->(oEnd:Object {id: $endObjectId}) RETURN rel",
					Map.of("namespaceName", namespaceName,
						   "startObjectId", startObjectId,
						   "endObjectId", endObjectId,
						   "label", label));

			assertTrue("Relationship node should exist with correct label", result.hasNext());
			assertNotNull("The relationship node should not be null", result.next().get("rel"));
		}
	}

	@Test
	public void shouldCreateRelationshipBetweenObjectsInSameClass() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String versioningType = "transient";  // Set versioning type to 'transient'
			String objectClassName = "ClassA";
			String label = "RELATES_TO";
			Double weight = 1.0;

			Map<String, String> objectIds = createNamespaceAndObjects(session, namespaceName, objectClassName, objectClassName, versioningType);
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

			// Verify: Relationship node was created
			Result result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(oStart:Object {id: $startObjectId})-[rel:RELATED_TO {label: $label}]->(oEnd:Object {id: $endObjectId}) RETURN rel",
					Map.of("namespaceName", namespaceName,
							"startObjectId", startObjectId,
						   "endObjectId", endObjectId,
						   "label", label));

			assertTrue("Relationship node should exist with correct label", result.hasNext());
			assertNotNull("The relationship node should not be null", result.next().get("rel"));
		}
	}

	@Test
	public void shouldUpdateRelationshipWeight() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String versioningType = "transient";  // Set versioning type to 'transient'
			String startObjectClassName = "ClassA";
			String endObjectClassName = "ClassB";
			String label = "RELATES_TO";
			Double initialWeight = 2.5;
			Double updatedWeight = 5.0;

			// Create Namespace and Objects
			Map<String, String> objectIds = createNamespaceAndObjects(session, namespaceName, startObjectClassName, endObjectClassName, versioningType);
			String startObjectId = objectIds.get("startObjectId");
			String endObjectId = objectIds.get("endObjectId");

			// Create initial relationship with weight
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.relationship($startObjectId, $endObjectId, $label, $weight, $token) YIELD rel RETURN rel",
						Map.of("startObjectId", startObjectId,
							   "endObjectId", endObjectId,
							   "label", label,
							   "weight", initialWeight,
							   "token", token));
				tx.commit();
				return null;
			});

			// Update the weight of the existing relationship
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.set.relationshipWeight($startObjectId, $endObjectId, $label, $weight, $token)",
						Map.of("startObjectId", startObjectId,
							   "endObjectId", endObjectId,
							   "label", label,
							   "weight", updatedWeight,
							   "token", token));
				tx.commit();
				return null;
			});

			// Verify: Relationship weight was updated
			Result result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object {id: $startObjectId})-[rel:RELATED_TO {label: $label}]->(:Object {id: $endObjectId}) RETURN rel.weight AS weight",
					Map.of("namespaceName", namespaceName,
							"startObjectId", startObjectId,
							"endObjectId", endObjectId,
							"label", label));

			assertTrue("Relationship node should exist with correct label", result.hasNext());
			Double retrievedWeight = result.single().get("weight").asDouble();
			assertTrue("The relationship weight should be updated", updatedWeight.equals(retrievedWeight));
		}
	}

	@Test
	public void shouldCreateOrUpdateRelationship() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String versioningType = "transient";  // Set versioning type to 'transient'
			String startObjectClassName = "ClassA";
			String endObjectClassName = "ClassB";
			String label = "RELATES_TO";
			Double initialWeight = 2.5;
			Double updatedWeight = 5.0;

			// Create Namespace and Objects
			Map<String, String> objectIds = createNamespaceAndObjects(session, namespaceName, startObjectClassName, endObjectClassName, versioningType);
			String startObjectId = objectIds.get("startObjectId");
			String endObjectId = objectIds.get("endObjectId");

			// Call the APOC procedure to create or update the relationship
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.set.relationship($startObjectId, $endObjectId, $label, $weight, $token) YIELD rel AS relationship",
						Map.of("startObjectId", startObjectId,
							   "endObjectId", endObjectId,
							   "label", label,
							   "weight", initialWeight,
							   "token", token));
				tx.commit();
				return null;
			});

			// Verify: Relationship node was created with the correct initial weight
			Result initialResult = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object {id: $startObjectId})-[rel:RELATED_TO {label: $label}]->(:Object {id: $endObjectId}) RETURN rel.weight AS weight",
					Map.of("namespaceName", namespaceName,
						   "startObjectId", startObjectId,
						   "endObjectId", endObjectId,
						   "label", label));

			assertTrue("Relationship node should exist with correct label after creation", initialResult.hasNext());
			Double retrievedInitialWeight = initialResult.single().get("weight").asDouble();
			assertTrue("The relationship weight should match the initial weight", initialWeight.equals(retrievedInitialWeight));

			// Call the APOC procedure again to update the weight of the existing relationship
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.set.relationship($startObjectId, $endObjectId, $label, $weight, $token) YIELD rel AS relationship",
						Map.of("startObjectId", startObjectId,
							   "endObjectId", endObjectId,
							   "label", label,
							   "weight", updatedWeight,
							   "token", token));
				tx.commit();
				return null;
			});

			// Verify: Relationship weight was updated
			Result updatedResult = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object {id: $startObjectId})-[rel:RELATED_TO {label: $label}]->(:Object {id: $endObjectId}) RETURN rel.weight AS weight",
					Map.of("namespaceName", namespaceName,
						   "startObjectId", startObjectId,
						   "endObjectId", endObjectId,
						   "label", label));

			assertTrue("Relationship node should exist with correct label after update", updatedResult.hasNext());
			Double retrievedUpdatedWeight = updatedResult.single().get("weight").asDouble();
			assertTrue("The relationship weight should be updated to the new value", updatedWeight.equals(retrievedUpdatedWeight));
		}
	}
}
