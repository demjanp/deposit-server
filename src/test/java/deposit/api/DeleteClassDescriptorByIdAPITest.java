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

public class DeleteClassDescriptorByIdAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	private Map<String, String> createNamespaceAndClassDescriptor(Session session, String namespaceName, String className, String descriptorName, String type, long order, String comment) {
		return session.writeTransaction(tx -> {
			// Create and set active Namespace with versioning set to 'transient'
			tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", 
					Map.of("namespaceName", namespaceName, "token", token));
			tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)",
					Map.of("namespaceName", namespaceName, "token", token));

			// Create Class
			tx.run("CALL deposit.create.class($className, -1, [], '', [], $token)", 
					Map.of("className", className, "token", token));

			// Create Descriptor
			Result descriptorResult = tx.run("CALL deposit.create.classDescriptor($className, $descriptorName, $type, $order, $comment, $token) YIELD node RETURN node.id AS descriptorId",
											 Map.of("className", className, "descriptorName", descriptorName, "type", type, "order", order, "comment", comment, "token", token));
			String descriptorId = descriptorResult.single().get("descriptorId").asString();

			tx.commit();
			return Map.of("className", className, "descriptorId", descriptorId);
		});
	}

	@Test
	public void shouldDeleteClassDescriptorById() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String className = "TestClass";
			String descriptorName = "TestDescriptor";
			String type = "String";
			long order = 1;
			String comment = "Test descriptor comment";

			Map<String, String> ids = createNamespaceAndClassDescriptor(session, namespaceName, className, descriptorName, type, order, comment);
			String descriptorId = ids.get("descriptorId");

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.classDescriptorById($descriptorId, $token)", 
						Map.of("descriptorId", descriptorId, "token", token));
				tx.commit();
				return null;
			});

			// Verify: Class Descriptor node was deleted
			Result result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class)-[:HAS_DESCRIPTOR]->(desc:Descriptor {id: $descriptorId}) RETURN desc",
										Map.of("namespaceName", namespaceName, "descriptorId", descriptorId));

			assertFalse("Class Descriptor node should be deleted", result.hasNext());
		}
	}
	
	@Test
	public void shouldDeleteClassDescriptorByName() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String className = "TestClass";
			String descriptorName = "TestDescriptor";
			String type = "String";
			long order = 1;
			String comment = "Test descriptor comment";

			createNamespaceAndClassDescriptor(session, namespaceName, className, descriptorName, type, order, comment);

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.classDescriptor($className, $descriptorName, $token)", 
						Map.of("className", className, "descriptorName", descriptorName, "token", token));
				tx.commit();
				return null;
			});

			// Verify: Class Descriptor node was deleted
			Result result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class {name: $className})-[:HAS_DESCRIPTOR]->(desc:Descriptor {name: $descriptorName}) RETURN desc",
										Map.of("namespaceName", namespaceName, "className", className, "descriptorName", descriptorName));

			assertFalse("Class Descriptor node should be deleted", result.hasNext());
		}
	}

	@Test
	public void shouldThrowExceptionWhenDeletingNonExistentClassDescriptor() {
		try (Session session = driver.session()) {
			String nonExistentDescriptorId = String.valueOf(UUID.randomUUID());

			Exception exception = assertThrows(Exception.class, () -> {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.delete.classDescriptorById($descriptorId, $token)", 
							Map.of("descriptorId", nonExistentDescriptorId, "token", token));
					tx.commit();
					return null;
				});
			});

			String expectedMessage = "Error deleting Class Descriptor " + nonExistentDescriptorId + ". It does not exist.";
			String actualMessage = exception.getMessage();

			assertTrue("Exception message should indicate that the class descriptor does not exist",
					actualMessage.contains(expectedMessage));
		}
	}

	@Test
	public void shouldUpdateClassDescriptorOrderAfterDeletion() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String className = "TestClass";
			String descriptorName1 = "TestDescriptor1";
			String descriptorName2 = "TestDescriptor2";
			String descriptorName3 = "TestDescriptor3";
			String type = "String";
			long order1 = 1;
			long order2 = 2;
			long order3 = 3;
			String comment = "Test descriptor comment";

			// Create namespace, class, and descriptors
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)",
					   Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)",
					   Map.of("namespaceName", namespaceName, "token", token));

				tx.run("CALL deposit.create.class($className, -1, [], '', [], $token)",
					   Map.of("className", className, "token", token));

				tx.run("CALL deposit.create.classDescriptor($className, $descriptorName1, $type, $order1, $comment, $token)",
					   Map.of("className", className, "descriptorName1", descriptorName1, "type", type, "order1", order1, "comment", comment, "token", token));
				tx.run("CALL deposit.create.classDescriptor($className, $descriptorName2, $type, $order2, $comment, $token)",
					   Map.of("className", className, "descriptorName2", descriptorName2, "type", type, "order2", order2, "comment", comment, "token", token));
				tx.run("CALL deposit.create.classDescriptor($className, $descriptorName3, $type, $order3, $comment, $token)",
					   Map.of("className", className, "descriptorName3", descriptorName3, "type", type, "order3", order3, "comment", comment, "token", token));

				tx.commit();
				return null;
			});

			// Get the ID of the second descriptor (TestDescriptor2)
			String descriptorIdToDelete = session.readTransaction(tx -> {
				Result result = tx.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class {name: $className})-[:HAS_DESCRIPTOR]->(d:Descriptor {name: $descriptorName2}) RETURN d.id AS id",
									   Map.of("namespaceName", namespaceName, "className", className, "descriptorName2", descriptorName2));
				return result.single().get("id").asString();
			});

			// Delete the second descriptor (TestDescriptor2)
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.classDescriptorById($descriptorId, $token)",
					   Map.of("descriptorId", descriptorIdToDelete, "token", token));
				tx.commit();
				return null;
			});

			// Verify that TestDescriptor2 is deleted
			Result resultDescriptor = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class {name: $className})-[:HAS_DESCRIPTOR]->(d:Descriptor {id: $descriptorId}) RETURN d",
												  Map.of("namespaceName", namespaceName, "className", className, "descriptorId", descriptorIdToDelete));
			assertFalse("TestDescriptor2 should be deleted", resultDescriptor.hasNext());

			// Verify that the remaining descriptors' order has been updated
			Result resultRemainingDescriptors = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class {name: $className})-[:HAS_DESCRIPTOR]->(d:Descriptor) RETURN d.name AS name, d.order AS order ORDER BY d.order",
														   Map.of("namespaceName", namespaceName, "className", className));

			// Assert that TestDescriptor1 has order 1 and TestDescriptor3 has order 2 (after TestDescriptor2 deletion)
			while (resultRemainingDescriptors.hasNext()) {
				Map<String, Object> descriptorRow = resultRemainingDescriptors.next().asMap();
				String descriptorName = (String) descriptorRow.get("name");
				Long order = (Long) descriptorRow.get("order");

				if (descriptorName.equals(descriptorName1)) {
					assertTrue("TestDescriptor1 should have order 1", order == 1);
				} else if (descriptorName.equals(descriptorName3)) {
					assertTrue("TestDescriptor3 should have order 2", order == 2);
				}
			}
		}
	}
}
