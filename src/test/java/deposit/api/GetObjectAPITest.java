package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GetObjectAPITest extends BaseNeo4jTest {

	private String token;

	@Override
	protected Class<?>[] getProcedureClasses() {
		return new Class<?>[]{GetObjectAPI.class, CreateNamespaceAPI.class, CreateObjectAPI.class, CreateDescriptorAPI.class, SetActiveNamespaceAPI.class};
	}

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

	private String createObject(Session session, List<String> classes) {
		return session.writeTransaction(tx -> {
			Result result = tx.run("CALL deposit.create.object($classes, [], [], $token) YIELD node RETURN node.id AS objectId",
					Map.of("classes", classes, "token", token));
			String objectId = result.single().get("objectId").asString();
			tx.commit();
			return objectId;
		});
	}

	private void createDescriptor(Session session, String objectId, String descriptorName, Object value) {
		session.writeTransaction(tx -> {
			tx.run("CALL deposit.create.descriptor($objectId, $descriptorName, $value, $token)",
					Map.of("objectId", objectId, "descriptorName", descriptorName, "value", value, "token", token));
			tx.commit();
			return null;
		});
	}

	@Test
	public void shouldFilterCorrectObjectsBasedOnClassAndDescriptor() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			createNamespace(session, namespaceName, "transient");
			
			// Create first object with class "ClassA" and descriptor "DescriptorA" with value "ValueA"
			List<String> classA = List.of("ClassA");
			String objectIdA = createObject(session, classA);
			createDescriptor(session, objectIdA, "DescriptorA", "ValueA");

			// Create second object with class "ClassB" and descriptor "DescriptorB" with value "ValueB"
			List<String> classB = List.of("ClassB");
			String objectIdB = createObject(session, classB);
			createDescriptor(session, objectIdB, "DescriptorB", "ValueB");

			// Create third object with class "ClassC" and descriptor "DescriptorC" with value "ValueC"
			List<String> classC = List.of("ClassC");
			String objectIdC = createObject(session, classC);
			createDescriptor(session, objectIdC, "DescriptorC", "ValueC");

			// Test case: Filter by class "ClassB"
			Result resultClassB = session.run("CALL deposit.get.object($classes, [], $token) YIELD node RETURN node.id AS objectId",
					Map.of("classes", classB, "token", token));

			assertTrue("Only one object should be returned when filtering by ClassB", resultClassB.hasNext());
			assertEquals("Object ID should match the object with ClassB", objectIdB, resultClassB.single().get("objectId").asString());

			// Test case: Filter by descriptor "DescriptorA" with value "ValueA"
			Result resultDescriptorA = session.run("CALL deposit.get.object([], [['DescriptorA', 'ValueA']], $token) YIELD node RETURN node.id AS objectId",
					Map.of("token", token));

			assertTrue("Only one object should be returned when filtering by DescriptorA and ValueA", resultDescriptorA.hasNext());
			assertEquals("Object ID should match the object with DescriptorA and ValueA", objectIdA, resultDescriptorA.single().get("objectId").asString());

			// Test case: Filter by class "ClassC" and descriptor "DescriptorC" with value "ValueC"
			Result resultClassCAndDescriptorC = session.run("CALL deposit.get.object($classes, [['DescriptorC', 'ValueC']], $token) YIELD node RETURN node.id AS objectId",
					Map.of("classes", classC, "token", token));

			assertTrue("Only one object should be returned when filtering by ClassC and DescriptorC with ValueC", resultClassCAndDescriptorC.hasNext());
			assertEquals("Object ID should match the object with ClassC and DescriptorC with ValueC", objectIdC, resultClassCAndDescriptorC.single().get("objectId").asString());

			// Test case: No object should be returned if class and descriptor do not match
			Result resultNoMatch = session.run("CALL deposit.get.object(['NonExistingClass'], [['NonExistingDescriptor', 'WrongValue']], $token) YIELD node RETURN node.id AS objectId",
					Map.of("token", token));

			assertTrue("No object should be returned when both class and descriptor do not match", !resultNoMatch.hasNext());
		}
	}

	@Test
	public void shouldReturnAllObjectsWhenNoParametersSpecified() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			createNamespace(session, namespaceName, "transient");

			// Create first object with class "ClassA" and descriptor "DescriptorA" with value "ValueA"
			List<String> classA = List.of("ClassA");
			String objectIdA = createObject(session, classA);
			createDescriptor(session, objectIdA, "DescriptorA", "ValueA");

			// Create second object with class "ClassB" and descriptor "DescriptorB" with value "ValueB"
			List<String> classB = List.of("ClassB");
			String objectIdB = createObject(session, classB);
			createDescriptor(session, objectIdB, "DescriptorB", "ValueB");

			// Create third object with class "ClassC" and descriptor "DescriptorC" with value "ValueC"
			List<String> classC = List.of("ClassC");
			String objectIdC = createObject(session, classC);
			createDescriptor(session, objectIdC, "DescriptorC", "ValueC");

			// Test case: Return all objects when no parameters are specified
			Result result = session.run("CALL deposit.get.object([], [], $token) YIELD node RETURN node.id AS objectId",
					Map.of("token", token));

			List<String> returnedObjectIds = result.list(r -> r.get("objectId").asString());

			// Ensure all object IDs are returned
			assertTrue("All created objects should be returned when no parameters are specified",
					returnedObjectIds.containsAll(List.of(objectIdA, objectIdB, objectIdC)));
			assertEquals("The number of returned objects should be 3", 3, returnedObjectIds.size());
		}
	}

}

