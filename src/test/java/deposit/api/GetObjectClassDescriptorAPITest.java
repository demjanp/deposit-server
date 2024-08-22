package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.neo4j.driver.Record;

import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class GetObjectClassDescriptorAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	private String createNamespaceAndObject(Session session, String namespaceName, String className) {
		return session.writeTransaction(tx -> {
			tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
			tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));

			Result result = tx.run("CALL deposit.create.object($className, [], [], $token) YIELD node RETURN node.id AS objectId",
					Map.of("className", className, "token", token));
			String objectId = result.single().get("objectId").asString();

			tx.commit();

			return objectId;
		});
	}

	private String createClassDescriptor(Session session, String className, String descriptorName, String type, long order, String comment) {
		return session.writeTransaction(tx -> {
			Result result = tx.run("CALL deposit.create.classDescriptor($className, $descriptorName, $type, $order, $comment, $token) YIELD node RETURN node.id AS descriptorId",
					Map.of("className", className, "descriptorName", descriptorName, "type", type, "order", order, "comment", comment, "token", token));
			String descriptorId = result.single().get("descriptorId").asString();
			tx.commit();
			return descriptorId;
		});
	}

	private String createDescriptor(Session session, String objectId, String descriptorName, Object value) {
		return session.writeTransaction(tx -> {
			Result result = tx.run("CALL deposit.create.descriptor($objectId, $descriptorName, $value, $token) YIELD node RETURN node.id AS descriptorId",
					Map.of("objectId", objectId, "descriptorName", descriptorName, "value", value, "token", token));
			String descriptorId = result.single().get("descriptorId").asString();
			tx.commit();

			return descriptorId;
		});
	}

	@Test
	public void shouldGetObjectClassDescriptorByName() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String className = "TestClass";
			String objectId = createNamespaceAndObject(session, namespaceName, className);

			// Create a class descriptor
			String descriptorName = "TestClassDescriptor";
			String classDescriptorId = createClassDescriptor(session, className, descriptorName, "String", 1, "Test descriptor comment");

			// Create an object descriptor with the same name as the class descriptor
			createDescriptor(session, objectId, descriptorName, "TestValue");

			// Retrieve the class descriptor using the procedure
			session.readTransaction(tx -> {
				Result result = tx.run("CALL deposit.get.objectClassDescriptor($objectId, $descriptorName, $token) YIELD node RETURN node",
						Map.of("objectId", objectId, "descriptorName", descriptorName, "token", token));

				assertTrue("Object Class Descriptor node should be retrieved", result.hasNext());

				// Assert that the retrieved node's id matches the classDescriptorId
				Record record = result.next();
				String retrievedId = record.get("node").asNode().get("id").asString();
				assertEquals("Retrieved Class Descriptor id should match the expected classDescriptorId", classDescriptorId, retrievedId);

				tx.commit();
				return null;
			});
		}
	}

	@Test
	public void shouldGetObjectClassDescriptorById() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String className = "TestClass";
			String objectId = createNamespaceAndObject(session, namespaceName, className);

			// Create a class descriptor
			String descriptorName = "TestDescriptor";
			String classDescriptorId = createClassDescriptor(session, className, descriptorName, "String", 1, "Test descriptor comment");

			// Create a descriptor linked to the object
			String descriptorValue = "TestValue";
			String descriptorId = createDescriptor(session, objectId, descriptorName, descriptorValue);

			// Retrieve the object class descriptor by ID using the procedure
			session.readTransaction(tx -> {
				Result result = tx.run("CALL deposit.get.objectClassDescriptorById($descriptorId, $token) YIELD node RETURN node",
						Map.of("descriptorId", descriptorId, "token", token));

				assertTrue("Object Class Descriptor node should be retrieved by ID", result.hasNext());

				// Assert that the retrieved node's id matches the classDescriptorId
				Record record = result.next();
				String retrievedId = record.get("node").asNode().get("id").asString();
				assertEquals("Retrieved Class Descriptor id should match the expected classDescriptorId", classDescriptorId, retrievedId);

				tx.commit();
				return null;
			});
		}
	}
}
