package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeleteAllAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	@Test
	public void shouldDeleteAllElementsInNamespace() {
		try (Session session = driver.session()) {
			// Step 1: Create a Namespace
			String namespaceName = "TestNamespace";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)",
						Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)",
						Map.of("namespaceName", namespaceName, "token", token));
				tx.commit();
				return null;
			});

			// Step 2: Create Classes and Class Descriptors
			String className1 = "Class1";
			String className2 = "Class2";
			createClassWithDescriptors(session, namespaceName, className1);
			createClassWithDescriptors(session, namespaceName, className2);

			// Step 3: Create Objects and Descriptors
			String objectId1 = createObjectWithClass(session, namespaceName, className1);
			String objectId2 = createObjectWithClass(session, namespaceName, className2);
			createDescriptor(session, objectId1, "Descriptor1", "Value1");
			createDescriptor(session, objectId2, "Descriptor2", "Value2");

			// Step 4: Create Class Relationship
			createClassRelationship(session, namespaceName, className1, className2);

			// Step 5: Create Object Relationship
			createRelationship(session, namespaceName, objectId1, objectId2, "RELATES_TO", 1.5);

			// Step 6: Create User Tool
			createUserTool(session, namespaceName, "UserTool1", "{\"key\":\"value\"}", "Comment");

			// Verify elements were created
			verifyElementsExist(session, namespaceName);

			// Step 7: Call delete.all to remove all elements
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.all($token)", Map.of("token", token));
				tx.commit();
				return null;
			});

			// Verify all elements were deleted
			verifyElementsDeleted(session, namespaceName);
		}
	}

	private void createClassWithDescriptors(Session session, String namespaceName, String className) {
		long order = 1;
		List<Double> position = List.of(1.0, 2.0);
		String comment = "Test comment";
		String descriptorName = "TestDescriptor";
		List<List<String>> descriptors = List.of(List.of(descriptorName, "String"));

		session.writeTransaction(tx -> {
			tx.run("CALL deposit.set.activeNamespace($namespaceName)", Map.of("namespaceName", namespaceName));
			tx.run("CALL deposit.create.class($className, $order, $position, $comment, $descriptors)",
					Map.of("className", className,
						   "order", order,
						   "position", position,
						   "comment", comment,
						   "descriptors", descriptors,
						   "token", token));
			tx.commit();
			return null;
		});
	}

	private String createObjectWithClass(Session session, String namespaceName, String className) {
		session.writeTransaction(tx -> {
			tx.run("CALL deposit.create.object($className, [], [], $token) YIELD node RETURN node.id AS objectId",
					Map.of("className", className, "token", token));
			tx.commit();
			return null;
		});

		Result result = session.run("""
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o:Object)-[:MEMBER_OF]->(c:Class {name: $className})
			RETURN o.id AS objectId
			""", Map.of("namespaceName", namespaceName, "className", className));

		assertTrue("Object should be created and linked to class", result.hasNext());
		return result.next().get("objectId").asString();
	}

	private void createDescriptor(Session session, String objectId, String descriptorName, String value) {
		session.writeTransaction(tx -> {
			tx.run("CALL deposit.create.descriptor($objectId, $descriptorName, $value, $token)",
					Map.of("objectId", objectId, "descriptorName", descriptorName, "value", value, "token", token));
			tx.commit();
			return null;
		});
	}

	private void createClassRelationship(Session session, String namespaceName, String startClassName, String endClassName) {
		session.writeTransaction(tx -> {
			tx.run("CALL deposit.create.classRelationship($startClassName, $endClassName, 'RELATED_TO', '', $token)",
					Map.of("startClassName", startClassName, "endClassName", endClassName, "token", token));
			tx.commit();
			return null;
		});
	}

	private void createRelationship(Session session, String namespaceName, String startObjectId, String endObjectId, String label, double weight) {
		session.writeTransaction(tx -> {
			tx.run("CALL deposit.create.relationship($startObjectId, $endObjectId, $label, $weight, $token) YIELD rel RETURN rel",
					Map.of("startObjectId", startObjectId, "endObjectId", endObjectId, "label", label, "weight", weight, "token", token));
			tx.commit();
			return null;
		});
	}

	private void createUserTool(Session session, String namespaceName, String userToolName, String data, String comment) {
		session.writeTransaction(tx -> {
			tx.run("CALL deposit.create.userTool($userToolName, $data, $comment, $token) YIELD node RETURN node",
					Map.of("userToolName", userToolName, "data", data, "comment", comment, "token", token));
			tx.commit();
			return null;
		});
	}

	private void verifyElementsExist(Session session, String namespaceName) {
		// Verify Classes
		assertTrue("Class1 should exist", session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: 'Class1'}) RETURN c", Map.of("namespaceName", namespaceName)).hasNext());
		assertTrue("Class2 should exist", session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: 'Class2'}) RETURN c", Map.of("namespaceName", namespaceName)).hasNext());

		// Verify Objects
		assertTrue("Object related to Class1 should exist", session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o:Object)-[:MEMBER_OF]->(c:Class {name: 'Class1'}) RETURN o", Map.of("namespaceName", namespaceName)).hasNext());
		assertTrue("Object related to Class2 should exist", session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o:Object)-[:MEMBER_OF]->(c:Class {name: 'Class2'}) RETURN o", Map.of("namespaceName", namespaceName)).hasNext());

		// Verify Relationships
		assertTrue("Relationship between objects should exist", session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o:Object)-[r:RELATED_TO]->(:Object) RETURN r", Map.of("namespaceName", namespaceName)).hasNext());

		// Verify Class Relationships
		assertTrue("Class Relationship should exist", session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class {name: 'Class1'})-[r:RELATED_TO]->(:Class {name: 'Class2'}) RETURN r", Map.of("namespaceName", namespaceName)).hasNext());

		// Verify Descriptors
		assertTrue("Descriptor1 should exist", session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object)-[:HAS_DESCRIPTOR]->(d:Descriptor {name: 'Descriptor1'}) RETURN d", Map.of("namespaceName", namespaceName)).hasNext());
		assertTrue("Descriptor2 should exist", session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object)-[:HAS_DESCRIPTOR]->(d:Descriptor {name: 'Descriptor2'}) RETURN d", Map.of("namespaceName", namespaceName)).hasNext());

		// Verify User Tools
		assertTrue("UserTool1 should exist", session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(ut:UserTool {name: 'UserTool1'}) RETURN ut", Map.of("namespaceName", namespaceName)).hasNext());
	}

	private void verifyElementsDeleted(Session session, String namespaceName) {
		// Verify no Classes
		assertFalse("No Classes should exist", session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class) RETURN c", Map.of("namespaceName", namespaceName)).hasNext());

		// Verify no Objects
		assertFalse("No Objects should exist", session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o:Object) RETURN o", Map.of("namespaceName", namespaceName)).hasNext());
		
		// Verify no Descriptors
		assertFalse("No Descriptors should exist", session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(d:Descriptor) RETURN d", Map.of("namespaceName", namespaceName)).hasNext());

		// Verify no User Tools
		assertFalse("No User Tools should exist", session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(ut:UserTool) RETURN ut", Map.of("namespaceName", namespaceName)).hasNext());
	}
}
