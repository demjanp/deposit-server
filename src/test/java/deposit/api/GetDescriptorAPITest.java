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

import static org.junit.Assert.assertTrue;

public class GetDescriptorAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	private String createNamespaceAndObject(Session session, String namespaceName, List<String> classes) {
		return session.writeTransaction(tx -> {
			tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
			tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));

			Result result = tx.run("CALL deposit.create.object($classes, [], [], $token) YIELD node RETURN node.id AS objectId",
								   Map.of("classes", classes, "token", token));
			String objectId = result.single().get("objectId").asString();

			tx.commit();

			return objectId;
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
	public void shouldGetDescriptorByName() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			List<String> classes = List.of("TestClass");

			String objectId = createNamespaceAndObject(session, namespaceName, classes);

			// Create a descriptor
			String descriptorName = "TestDescriptor";
			String descriptorValue = "TestValue";
			createDescriptor(session, objectId, descriptorName, descriptorValue);

			// Retrieve the descriptor using the procedure
			session.readTransaction(tx -> {
				Result result = tx.run("CALL deposit.get.descriptor($objectId, $descriptorName, $token) YIELD node RETURN node",
						Map.of("objectId", objectId, "descriptorName", descriptorName, "token", token));

				assertTrue("Descriptor node should be retrieved", result.hasNext());
				tx.commit();
				return null;
			});
		}
	}

	@Test
	public void shouldGetDescriptorById() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			List<String> classes = List.of("TestClass");

			String objectId = createNamespaceAndObject(session, namespaceName, classes);

			// Create a descriptor
			String descriptorName = "TestDescriptor";
			String descriptorValue = "TestValue";
			String descriptorId = createDescriptor(session, objectId, descriptorName, descriptorValue);

			// Retrieve the descriptor using the procedure
			session.readTransaction(tx -> {
				Result result = tx.run("CALL deposit.get.descriptorById($descriptorId, $token) YIELD node RETURN node",
						Map.of("descriptorId", descriptorId, "token", token));

				assertTrue("Descriptor node should be retrieved", result.hasNext());
				tx.commit();
				return null;
			});
		}
	}

	@Test
	public void shouldGetAllDescriptorsForObject() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			List<String> classes = List.of("TestClass");

			String objectId = createNamespaceAndObject(session, namespaceName, classes);

			// Create multiple descriptors
			String descriptorName1 = "Descriptor1";
			String descriptorValue1 = "Value1";
			String descriptorName2 = "Descriptor2";
			String descriptorValue2 = "Value2";
			createDescriptor(session, objectId, descriptorName1, descriptorValue1);
			createDescriptor(session, objectId, descriptorName2, descriptorValue2);

			// Retrieve all descriptors for the object using the procedure
			session.readTransaction(tx -> {
				Result result = tx.run("CALL deposit.get.descriptor($objectId, '', $token) YIELD node RETURN node",
						Map.of("objectId", objectId, "token", token));

				int count = 0;
				while (result.hasNext()) {
					result.next();
					count++;
				}

				assertTrue("Should retrieve both descriptors", count == 2);
				tx.commit();
				return null;
			});
		}
	}
}
