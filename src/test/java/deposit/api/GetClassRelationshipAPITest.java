package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;

import java.io.IOException;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class GetClassRelationshipAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	private void createNamespaceAndClasses(Session session, String namespaceName, String startClassName, String endClassName) {
		session.writeTransaction(tx -> {
			// Create the Namespace
			tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
			tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));

			// Create the Start Class
			tx.run("CALL deposit.create.class($className, -1, [], '', [], $token) YIELD node RETURN node.id AS startClassId",
					Map.of("className", startClassName, "token", token));

			// Create the End Class
			tx.run("CALL deposit.create.class($className, -1, [], '', [], $token) YIELD node RETURN node.id AS endClassId",
					Map.of("className", endClassName, "token", token));

			tx.commit();
			return null;
		});
	}

	@Test
	public void shouldGetClassRelationshipById() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String startClassName = "StartClass";
			String endClassName = "EndClass";
			String label = "RELATES_TO";
			String comment = "This is a test relationship.";

			createNamespaceAndClasses(session, namespaceName, startClassName, endClassName);

			// Create the class relationship and get its ID
			String relationshipId = session.writeTransaction(tx -> {
				Result createResult = tx.run("CALL deposit.create.classRelationship($startClassName, $endClassName, $label, $comment, $token) YIELD rel RETURN rel.id AS relationshipId",
						Map.of("startClassName", startClassName,
							   "endClassName", endClassName,
							   "label", label,
							   "comment", comment,
							   "token", token));
				return createResult.single().get("relationshipId").asString();
			});

			// Retrieve the class relationship by ID
			Result result = session.run("CALL deposit.get.classRelationshipById($relationshipId, $token) YIELD rel RETURN rel",
					Map.of("relationshipId", relationshipId, "token", token));

			assertTrue("Should retrieve the class relationship by ID", result.hasNext());
			assertNotNull("The relationship node should not be null", result.next().get("relationship"));
		}
	}

	@Test
	public void shouldReturnNullForNonExistentClassRelationshipById() {
		try (Session session = driver.session()) {
			String nonExistentRelationshipId = "non-existent-id";

			// Call the get.classRelationshipById procedure with a non-existent ID
			Result result = session.run("CALL deposit.get.classRelationshipById($relationshipId, $token) YIELD rel RETURN rel",
					Map.of("relationshipId", nonExistentRelationshipId, "token", token));

			assertFalse("Should not retrieve any class relationship", result.hasNext());
		}
	}

	@Test
	public void shouldGetClassRelationshipByClasses() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String startClassName = "StartClass";
			String endClassName = "EndClass";
			String label = "RELATES_TO";
			String comment = "This is a test relationship.";

			createNamespaceAndClasses(session, namespaceName, startClassName, endClassName);

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.classRelationship($startClassName, $endClassName, $label, $comment, $token) YIELD rel RETURN rel",
						Map.of("startClassName", startClassName,
							   "endClassName", endClassName,
							   "label", label,
							   "comment", comment,
							   "token", token));
				tx.commit();
				return null;
			});

			// Retrieve the class relationship by start and end class
			Result result = session.run("CALL deposit.get.classRelationship($startClassName, $endClassName, $label, $token) YIELD rel RETURN rel",
					Map.of("startClassName", startClassName, "endClassName", endClassName, "label", label, "token", token));

			assertTrue("Should retrieve the class relationship by classes", result.hasNext());
			assertNotNull("The relationship node should not be null", result.next().get("relationship"));
		}
	}
}
