package deposit.api;

import deposit.BaseNeo4jTest;
import org.junit.Test;
import org.neo4j.driver.Session;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SetActiveNamespaceAPITest extends BaseNeo4jTest {

    @Test
    public void shouldSetActiveNamespace() {
        try (Session session = driver.session()) {
            String namespaceName = "ExistingNamespace";
            // First, create the namespace to ensure it exists
            session.writeTransaction(tx -> {
                tx.run("CREATE (:Namespace {name: $namespaceName, versioningType: 'transient'})", Map.of("namespaceName", namespaceName));
                tx.commit();
                return null;
            });

            // Call the procedure to set the active namespace
            session.writeTransaction(tx -> {
                tx.run("CALL deposit.set.activeNamespace($namespaceName)", Map.of("namespaceName", namespaceName));
                tx.commit();
                return null;
            });

            // Verify that the active namespace is set in the session context using the helper procedure
            var result = session.run("CALL deposit.get.activeNamespace()");
            assertTrue(result.hasNext());
            var node = result.next().get("node").asNode();
            assertEquals(namespaceName, node.get("name").asString());
        }
    }

    @Test
    public void shouldThrowExceptionIfNamespaceDoesNotExist() {
        try (Session session = driver.session()) {
            String namespaceName = "NonExistentNamespace";
            // Try to set a non-existent namespace as active, which should throw an exception
            try {
                session.writeTransaction(tx -> {
                    tx.run("CALL deposit.set.activeNamespace($namespaceName)", Map.of("namespaceName", namespaceName));
                    tx.commit();
                    return null;
                });
                fail("Expected RuntimeException not thrown");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage().contains("Transaction failed: Namespace " + namespaceName + " doesn't exist"));
            }
        }
    }

    @Test
    public void shouldHandleTransientVersioning() {
        try (Session session = driver.session()) {
            String namespaceName = "TransientNamespace";
            // Create the namespace with transient versioning
            session.writeTransaction(tx -> {
                tx.run("CREATE (:Namespace {name: $namespaceName, versioningType: 'transient'})", Map.of("namespaceName", namespaceName));
                tx.commit();
                return null;
            });

            // Call the procedure to set the active namespace
            session.writeTransaction(tx -> {
                tx.run("CALL deposit.set.activeNamespace($namespaceName)", Map.of("namespaceName", namespaceName));
                tx.commit();
                return null;
            });

            // Verify the versioning behavior
            var result = session.run("MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(a:Archive) RETURN a", Map.of("namespaceName", namespaceName));
            assertTrue(result.hasNext());
        }
    }


    @Test
    public void shouldHandleVersioning() {
        try (Session session = driver.session()) {
            String namespaceName = "PermanentNamespace";
            // Create the namespace with versioning
            session.writeTransaction(tx -> {
                tx.run("CREATE (:Namespace {name: $namespaceName, versioningType: 'permanent'})", Map.of("namespaceName", namespaceName));
                tx.commit();
                return null;
            });

            // Call the procedure to set the active namespace
            session.writeTransaction(tx -> {
                tx.run("CALL deposit.set.activeNamespace($namespaceName)", Map.of("namespaceName", namespaceName));
                tx.commit();
                return null;
            });

            // Verify the versioning behavior
            var result = session.run("MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(a:Archive) RETURN a", Map.of("namespaceName", namespaceName));
            assertTrue(result.hasNext());

            var versionResult = session.run("MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(a:Archive)-[:CONTAINS]->(v:Version) RETURN v", Map.of("namespaceName", namespaceName));
            assertTrue(versionResult.hasNext());
        }
    }

}
