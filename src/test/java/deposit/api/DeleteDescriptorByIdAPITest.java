package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class DeleteDescriptorByIdAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	private Map<String, String> createNamespaceAndDescriptor(Session session, String namespaceName, List<String> objectClasses, String descriptorName, Object descriptorValue) {
		return session.writeTransaction(tx -> {
			// Create and set active Namespace with versioning set to 'transient'
			tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", 
					Map.of("namespaceName", namespaceName, "token", token));
			tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)",
					Map.of("namespaceName", namespaceName, "token", token));
			
			// Create Object
			Result objectResult = tx.run("CALL deposit.create.object($classes, [], [], $token) YIELD node RETURN node.id AS objectId",
										 Map.of("classes", objectClasses, "token", token));
			String objectId = objectResult.single().get("objectId").asString();

			// Create Descriptor
			Result descriptorResult = tx.run("CALL deposit.create.descriptor($objectId, $descriptorName, $descriptorValue, $token) YIELD node RETURN node.id AS descriptorId",
											 Map.of("objectId", objectId, "descriptorName", descriptorName, "descriptorValue", descriptorValue, "token", token));
			String descriptorId = descriptorResult.single().get("descriptorId").asString();

			tx.commit();
			return Map.of("objectId", objectId, "descriptorId", descriptorId);
		});
	}

	@Test
	public void shouldDeleteDescriptorById() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			List<String> objectClasses = List.of("ClassA");
			String descriptorName = "TestDescriptor";
			Object descriptorValue = "TestValue";

			Map<String, String> ids = createNamespaceAndDescriptor(session, namespaceName, objectClasses, descriptorName, descriptorValue);
			String descriptorId = ids.get("descriptorId");

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.descriptorById($descriptorId, $token)", 
						Map.of("descriptorId", descriptorId, "token", token));
				tx.commit();
				return null;
			});

			// Verify: Descriptor node was deleted
			Result result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object)-[:HAS_DESCRIPTOR]->(desc:Descriptor {id: $descriptorId}) RETURN desc",
										Map.of("namespaceName", namespaceName, "descriptorId", descriptorId));

			assertFalse("Descriptor node should be deleted", result.hasNext());
		}
	}
	
	@Test
	public void shouldDeleteDescriptorByName() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			List<String> objectClasses = List.of("ClassA");
			String descriptorName = "TestDescriptor";
			Object descriptorValue = "TestValue";

			Map<String, String> ids = createNamespaceAndDescriptor(session, namespaceName, objectClasses, descriptorName, descriptorValue);
			String objectId = ids.get("objectId");
			
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.descriptor($objectId, $descriptorName, $token)", 
						Map.of("objectId", objectId, "descriptorName", descriptorName, "token", token));
				tx.commit();
				return null;
			});
			
			// Verify: Descriptor node was deleted
			Result result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object {id: $objectId})-[:HAS_DESCRIPTOR]->(desc:Descriptor {name: $descriptorName}) RETURN desc",
										Map.of("namespaceName", namespaceName, "objectId", objectId, "descriptorName", descriptorName));

			assertFalse("Descriptor node should be deleted", result.hasNext());
		}
	}

	@Test
	public void shouldThrowExceptionWhenDeletingNonExistentDescriptor() {
		try (Session session = driver.session()) {
			String nonExistentDescriptorId = String.valueOf(UUID.randomUUID());

			Exception exception = assertThrows(Exception.class, () -> {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.delete.descriptorById($descriptorId, $token)", 
							Map.of("descriptorId", nonExistentDescriptorId, "token", token));
					tx.commit();
					return null;
				});
			});

			String expectedMessage = "Error deleting Descriptor " + nonExistentDescriptorId + ". It does not exist.";
			String actualMessage = exception.getMessage();

			assertTrue("Exception message should indicate that the descriptor does not exist",
					actualMessage.contains(expectedMessage));
		}
	}
}
