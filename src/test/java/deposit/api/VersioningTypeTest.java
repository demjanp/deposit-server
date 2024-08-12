package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class VersioningTypeTest extends BaseNeo4jTest {

	private String token;

	@Override
	protected Class<?>[] getProcedureClasses() {
		return new Class<?>[]{CreateObjectAPI.class, CreateNamespaceAPI.class, SetActiveNamespaceAPI.class};
	}

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	@Test
	public void shouldSetAndRetrieveVersioningTypeDuringObjectCreation() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String versioningType = "transient";  // Set versioning type to 'transient'
			List<String> objectClasses = List.of("ClassA");

			session.writeTransaction(tx -> {
				// Create and set active Namespace with versioning type
				tx.run("CALL deposit.create.namespace($namespaceName, '', $versioningType, $token)",
						Map.of("namespaceName", namespaceName, "versioningType", versioningType, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)",
						Map.of("namespaceName", namespaceName, "token", token));
				ServerContext.setActiveNamespace(namespaceName, token);
				tx.commit();
				return null;
			});

			// Check if the versioning type is correctly stored in the ServerContext
			String storedVersioningType = ServerContext.getVersioningType(token);
			assertEquals("Versioning type should be 'transient' after setting the namespace","transient", storedVersioningType);

			// Create an Object
			session.writeTransaction(tx -> {
				Result result = tx.run("CALL deposit.create.object($classes, [], [], $token) YIELD node RETURN node.id AS objectId",
						Map.of("classes", objectClasses, "token", token));

				String objectId = result.single().get("objectId").asString();
				assertNotNull("Object ID should not be null", objectId);
				tx.commit();
				return null;
			});

			// Check if the versioning type is still correctly stored in the ServerContext after object creation
			storedVersioningType = ServerContext.getVersioningType(token);
			assertEquals("Versioning type should still be 'transient' after creating an object", "transient", storedVersioningType);
		}
	}
}
