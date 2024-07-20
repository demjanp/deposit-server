package deposit.api;

import deposit.BaseNeo4jTest;
import org.junit.Test;
import org.neo4j.driver.Session;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SetActiveNamespaceAPITest extends BaseNeo4jTest {

    @Override
    protected Class<?>[] getProcedureClasses() {
        return new Class<?>[]{SetActiveNamespaceAPI.class, GetActiveNamespaceAPI.class};
    }

    @Test
    public void shouldSetActiveNamespace() {
        try (Session session = driver.session()) {
            String name = "ExistingNamespace";
            // First, create the namespace to ensure it exists
            session.writeTransaction(tx -> {
                tx.run("CREATE (:Namespace {name: $name})", Map.of("name", name));
                tx.commit();
                return null;
            });

            // Call the procedure to set the active namespace
            session.writeTransaction(tx -> {
                tx.run("CALL deposit.set.activeNamespace($name)", Map.of("name", name));
                tx.commit();
                return null;
            });

            // Verify that the active namespace is set in the session context using the helper procedure
            var result = session.run("CALL deposit.get.activeNamespace()");
            assertTrue(result.hasNext());
            var record = result.next();
            assertEquals(name, record.get("value").asString());
        }
    }

    @Test
    public void shouldThrowExceptionIfNamespaceDoesNotExist() {
        try (Session session = driver.session()) {
            String name = "NonExistentNamespace";
            // Try to set a non-existent namespace as active, which should throw an exception
            try {
                session.writeTransaction(tx -> {
                    tx.run("CALL deposit.set.activeNamespace($name)", Map.of("name", name));
                    tx.commit();
                    return null;
                });
                fail("Expected RuntimeException not thrown");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage().contains("Transaction failed: Namespace " + name + " doesn't exist"));
            }
        }
    }
}
