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

public class DeleteObjectAPITest extends BaseNeo4jTest {

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

	private Map<String, String> createObjectAndDescriptor(Session session, String namespaceName, String objectClassName, String descriptorName, String descriptorValue) {
		return session.writeTransaction(tx -> {
			Result result = tx.run("CALL deposit.create.object($objectClassName, [], [], $token) YIELD node RETURN node.id AS objectId",
					Map.of("objectClassName", objectClassName, "token", token));
			String objectId = result.single().get("objectId").asString();

			tx.run("CALL deposit.create.descriptor($objectId, $descriptorName, $value, $token)",
					Map.of("objectId", objectId, "descriptorName", descriptorName, "value", descriptorValue, "token", token));

			tx.commit();

			return Map.of("objectId", objectId);
		});
	}

	private void createRelationship(Session session, String startObjectId, String endObjectId, String label) {
		session.writeTransaction(tx -> {
			tx.run("CALL deposit.create.relationship($startObjectId, $endObjectId, $label, 1.0, $token) YIELD node RETURN node",
					Map.of("startObjectId", startObjectId,
						   "endObjectId", endObjectId,
						   "label", label,
						   "token", token));
			tx.commit();
			return null;
		});
	}

	@Test
	public void shouldDeleteObjectAndRelatedData() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String versioningType = "transient";
			String objectClassName = "TestClass";

			createNamespace(session, namespaceName, versioningType);
			Map<String, String> objectData = createObjectAndDescriptor(session, namespaceName, objectClassName, "TestDescriptor", "TestValue");
			String objectId = objectData.get("objectId");

			String endObjectId = createObjectAndDescriptor(session, namespaceName, objectClassName, "EndDescriptor", "EndValue").get("objectId");
			createRelationship(session, objectId, endObjectId, "RELATES_TO");

			// Call the delete.object procedure
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.object($objectId, $token)",
						Map.of("objectId", objectId, "token", token));
				tx.commit();
				return null;
			});

			Result result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o:Object {id: $objectId}) RETURN o", Map.of("namespaceName", namespaceName, "objectId", objectId));
			assertFalse("Object should have been deleted", result.hasNext());
		}
	}

	@Test(expected = RuntimeException.class)
	public void shouldThrowExceptionIfObjectDoesNotExist() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String versioningType = "transient";
			String nonExistingObjectId = UUID.randomUUID().toString();

			createNamespace(session, namespaceName, versioningType);

			// Attempt to delete a non-existing object
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.object($objectId, $token)",
						Map.of("objectId", nonExistingObjectId, "token", token));
				tx.commit();
				return null;
			});
		}
	}

	@Test
	public void shouldNotDeleteOtherObjectsOrData() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String versioningType = "transient";
			String objectClassName = "TestClass";

			createNamespace(session, namespaceName, versioningType);
			Map<String, String> objectData1 = createObjectAndDescriptor(session, namespaceName, objectClassName, "Descriptor1", "Value1");
			Map<String, String> objectData2 = createObjectAndDescriptor(session, namespaceName, objectClassName, "Descriptor2", "Value2");

			String objectIdToDelete = objectData1.get("objectId");
			String objectIdToKeep = objectData2.get("objectId");

			// Call the delete.object procedure to delete the first object
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.object($objectId, $token)",
						Map.of("objectId", objectIdToDelete, "token", token));
				tx.commit();
				return null;
			});

			// Verify: The deleted object is gone
			Result resultDeleted = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o:Object {id: $objectId}) RETURN o", Map.of("namespaceName", namespaceName, "objectId", objectIdToDelete));
			assertFalse("Deleted object should not exist", resultDeleted.hasNext());

			// Verify: The other object still exists
			Result resultExisting = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o:Object {id: $objectId}) RETURN o", Map.of("namespaceName", namespaceName, "objectId", objectIdToKeep));
			assertTrue("Other object should still exist", resultExisting.hasNext());
		}
	}
}
