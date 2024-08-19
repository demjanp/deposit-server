package deposit.api;

import deposit.BaseNeo4jTest;
import org.junit.Test;
import org.neo4j.driver.Session;

import java.util.Map;

import static org.junit.Assert.assertFalse;

public class DeleteNamespaceAPITest extends BaseNeo4jTest {

	@Test
	public void shouldDeleteNamespaceNode() {
		try (Session session = driver.session()) {
			String namespaceName = "NamespaceToDelete";

			// Create a namespace to delete
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', '', '')",
						Map.of("namespaceName", namespaceName));
				tx.commit();
				return null;
			});

			// Delete the created namespace
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.namespace($namespaceName, '')",
						Map.of("namespaceName", namespaceName));
				tx.commit();
				return null;
			});

			// Verify the namespace is deleted
			var result = session.run("MATCH (n:Namespace {name: $namespaceName}) RETURN n",
					Map.of("namespaceName", namespaceName));
			assertFalse(result.hasNext());
		}
	}

	@Test
	public void shouldNotThrowExceptionIfNamespaceDoesNotExist() {
		try (Session session = driver.session()) {
			String namespaceName = "NonExistentNamespace";

			// Attempt to delete a non-existent namespace
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.namespace($namespaceName, '')",
						Map.of("namespaceName", namespaceName));
				tx.commit();
				return null;
			});

			// Since no exception is expected, we only verify that the transaction completes without errors
		}
	}

	@Test
	public void shouldDeleteNamespaceAndAssociatedNodes() {
		try (Session session = driver.session()) {
			String namespaceName = "NamespaceWithRelatedNodes";

			// Create a namespace and related nodes
			session.writeTransaction(tx -> {
				tx.run("CREATE (n:Namespace {name: $namespaceName})-[:CONTAINS]->(:RelatedNode)",
						Map.of("namespaceName", namespaceName));
				tx.commit();
				return null;
			});

			// Delete the namespace
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.namespace($namespaceName, '')",
						Map.of("namespaceName", namespaceName));
				tx.commit();
				return null;
			});

			// Verify both namespace and related nodes are deleted
			var result = session.run("MATCH (n:Namespace {name: $namespaceName}) RETURN n",
					Map.of("namespaceName", namespaceName));
			assertFalse(result.hasNext());

			result = session.run("MATCH (n:RelatedNode) RETURN n");
			assertFalse(result.hasNext());
		}
	}
}
