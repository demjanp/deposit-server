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

public class CreateClassRelationshipAPITest extends BaseNeo4jTest {

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
	public void shouldCreateClassRelationship() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String startClassName = "StartClass";
			String endClassName = "EndClass";
			String label = "RELATES_TO";
			String comment = "This is a test relationship.";

			createNamespaceAndClasses(session, namespaceName, startClassName, endClassName);

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.classRelationship($startClassName, $endClassName, $label, $comment, $token) YIELD node RETURN node",
						Map.of("startClassName", startClassName,
							   "endClassName", endClassName,
							   "label", label,
							   "comment", comment,
							   "token", token));
				tx.commit();
				return null;
			});

			// Verify: Relationship node was created
			Result result = session.run("MATCH (cStart:Class {name: $startClassName})-[r:RS]->(rel:Relationship {label: $label})-[r2:RE]->(cEnd:Class {name: $endClassName}) RETURN rel",
					Map.of("startClassName", startClassName,
						   "endClassName", endClassName,
						   "label", label));

			assertTrue("Relationship node should exist with correct label", result.hasNext());
			assertNotNull("The relationship node should not be null", result.next().get("rel"));
		}
	}

	@Test(expected = Exception.class)
	public void shouldNotCreateDuplicateClassRelationship() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String startClassName = "StartClass";
			String endClassName = "EndClass";
			String label = "RELATES_TO";
			String comment = "This is a test relationship.";

			createNamespaceAndClasses(session, namespaceName, startClassName, endClassName);

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.classRelationship($startClassName, $endClassName, $label, $comment, $token) YIELD node RETURN node",
						Map.of("startClassName", startClassName,
							   "endClassName", endClassName,
							   "label", label,
							   "comment", comment,
							   "token", token));
				tx.commit();
				return null;
			});

			// Attempt to create the same relationship again, should throw an exception
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.classRelationship($startClassName, $endClassName, $label, $comment, $token) YIELD node RETURN node",
						Map.of("startClassName", startClassName,
							   "endClassName", endClassName,
							   "label", label,
							   "comment", comment,
							   "token", token));
				tx.commit();
				return null;
			});
		}
	}

	@Test
	public void shouldSetClassRelationshipComment() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String startClassName = "StartClass";
			String endClassName = "EndClass";
			String label = "RELATES_TO";
			String initialComment = "Initial comment.";
			String updatedComment = "Updated comment.";

			// Step 1: Create Namespace, Classes, and Relationship
			createNamespaceAndClasses(session, namespaceName, startClassName, endClassName);

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.classRelationship($startClassName, $endClassName, $label, $comment, $token) YIELD node RETURN node",
						Map.of("startClassName", startClassName,
							   "endClassName", endClassName,
							   "label", label,
							   "comment", initialComment,
							   "token", token));
				tx.commit();
				return null;
			});

			// Step 2: Update the Relationship Comment
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.set.classRelationshipComment($startClassName, $endClassName, $label, $comment, $token)",
						Map.of("startClassName", startClassName,
							   "endClassName", endClassName,
							   "label", label,
							   "comment", updatedComment,
							   "token", token));
				tx.commit();
				return null;
			});

			// Step 3: Verify the Relationship Comment was Updated
			Result result = session.run("MATCH (cStart:Class {name: $startClassName})-[r:RS]->(rel:Relationship {label: $label})-[r2:RE]->(cEnd:Class {name: $endClassName}) RETURN rel.comment AS comment",
					Map.of("startClassName", startClassName,
						   "endClassName", endClassName,
						   "label", label));

			assertTrue("Relationship comment should be updated", result.hasNext());
			String returnedComment = result.next().get("comment").asString();
			assertTrue("The comment should be the updated one", updatedComment.equals(returnedComment));
		}
	}

}
