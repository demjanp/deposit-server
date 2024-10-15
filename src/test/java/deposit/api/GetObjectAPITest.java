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

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	@Test
	public void shouldFilterCorrectObjectsBasedOnClassAndDescriptor() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			createNamespace(session, namespaceName, "transient", token);
			
			// Create first object with class "ClassA" and descriptor "DescriptorA" with value "ValueA"
			String classA = "ClassA";
			String objectIdA = createObject(session, classA, token);
			createDescriptor(session, objectIdA, "DescriptorA", "ValueA", token);

			// Create second object with class "ClassB" and descriptor "DescriptorB" with value "ValueB"
			String classB = "ClassB";
			String objectIdB = createObject(session, classB, token);
			createDescriptor(session, objectIdB, "DescriptorB", "ValueB", token);

			// Create third object with class "ClassC" and descriptor "DescriptorC" with value "ValueC"
			String classC = "ClassC";
			String objectIdC = createObject(session, classC, token);
			createDescriptor(session, objectIdC, "DescriptorC", "ValueC", token);

			// Test case: Filter by class "ClassB"
			Result resultClassB = session.run("CALL deposit.get.object($className, [], $token) YIELD node RETURN node.id AS objectId",
					Map.of("className", classB, "token", token));

			assertTrue("Only one object should be returned when filtering by ClassB", resultClassB.hasNext());
			assertEquals("Object ID should match the object with ClassB", objectIdB, resultClassB.single().get("objectId").asString());

			// Test case: Filter by descriptor "DescriptorA" with value "ValueA"
			Result resultDescriptorA = session.run("CALL deposit.get.object('', [['DescriptorA', 'ValueA']], $token) YIELD node RETURN node.id AS objectId",
					Map.of("token", token));

			assertTrue("Only one object should be returned when filtering by DescriptorA and ValueA", resultDescriptorA.hasNext());
			assertEquals("Object ID should match the object with DescriptorA and ValueA", objectIdA, resultDescriptorA.single().get("objectId").asString());

			// Test case: Filter by class "ClassC" and descriptor "DescriptorC" with value "ValueC"
			Result resultClassCAndDescriptorC = session.run("CALL deposit.get.object($className, [['DescriptorC', 'ValueC']], $token) YIELD node RETURN node.id AS objectId",
					Map.of("className", classC, "token", token));

			assertTrue("Only one object should be returned when filtering by ClassC and DescriptorC with ValueC", resultClassCAndDescriptorC.hasNext());
			assertEquals("Object ID should match the object with ClassC and DescriptorC with ValueC", objectIdC, resultClassCAndDescriptorC.single().get("objectId").asString());

			// Test case: No object should be returned if class and descriptor do not match
			Result resultNoMatch = session.run("CALL deposit.get.object('NonExistingClass', [['NonExistingDescriptor', 'WrongValue']], $token) YIELD node RETURN node.id AS objectId",
					Map.of("token", token));

			assertTrue("No object should be returned when both class and descriptor do not match", !resultNoMatch.hasNext());
		}
	}

	@Test
	public void shouldReturnAllObjectsWhenNoParametersSpecified() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			createNamespace(session, namespaceName, "transient", token);

			// Create first object with class "ClassA" and descriptor "DescriptorA" with value "ValueA"
			String classA = "ClassA";
			String objectIdA = createObject(session, classA, token);
			createDescriptor(session, objectIdA, "DescriptorA", "ValueA", token);

			// Create second object with class "ClassB" and descriptor "DescriptorB" with value "ValueB"
			String classB = "ClassB";
			String objectIdB = createObject(session, classB, token);
			createDescriptor(session, objectIdB, "DescriptorB", "ValueB", token);

			// Create third object with class "ClassC" and descriptor "DescriptorC" with value "ValueC"
			String classC = "ClassC";
			String objectIdC = createObject(session, classC, token);
			createDescriptor(session, objectIdC, "DescriptorC", "ValueC", token);

			// Test case: Return all objects when no parameters are specified
			Result result = session.run("CALL deposit.get.object('', [], $token) YIELD node RETURN node.id AS objectId",
					Map.of("token", token));

			List<String> returnedObjectIds = result.list(r -> r.get("objectId").asString());

			// Ensure all object IDs are returned
			assertTrue("All created objects should be returned when no parameters are specified",
					returnedObjectIds.containsAll(List.of(objectIdA, objectIdB, objectIdC)));
			assertEquals("The number of returned objects should be 3", 3, returnedObjectIds.size());
		}
	}

}

