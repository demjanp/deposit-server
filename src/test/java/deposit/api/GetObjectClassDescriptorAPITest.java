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

	@Test
	public void shouldGetObjectClassDescriptorByName() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String className = "TestClass";
			createNamespace(session, namespaceName, "transient", token);
			String objectId = createObject(session, className, token);
			
			// Create a class descriptor
			String descriptorName = "TestClassDescriptor";
			String classDescriptorId = createClassDescriptor(session, className, descriptorName, "String", 1, "Test descriptor comment", token);

			// Create an object descriptor with the same name as the class descriptor
			createDescriptor(session, objectId, descriptorName, "TestValue", token);

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
			createNamespace(session, namespaceName, "transient", token);
			String objectId = createObject(session, className, token);

			// Create a class descriptor
			String descriptorName = "TestDescriptor";
			String classDescriptorId = createClassDescriptor(session, className, descriptorName, "String", 1, "Test descriptor comment", token);

			// Create a descriptor linked to the object
			String descriptorValue = "TestValue";
			String descriptorId = createDescriptor(session, objectId, descriptorName, descriptorValue, token);

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
