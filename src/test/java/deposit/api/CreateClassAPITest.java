package deposit.api;

import deposit.BaseNeo4jTest;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class CreateClassAPITest extends BaseNeo4jTest {

    @Override
    protected Class<?>[] getProcedureClasses() {
        return new Class<?>[]{CreateClassAPI.class, CreateNamespaceAPI.class, SetActiveNamespaceAPI.class};
    }

    @Test
    public void shouldCreateClassNode() {
        try (Session session = driver.session()) {
            // Setup: Create a Namespace to contain the Class
            String namespaceName = "TestNamespace";
            String versioningType = "transient";
            session.writeTransaction(tx -> {
                tx.run("CALL deposit.create.namespace($namespaceName, '', $versioningType)", Map.of("namespaceName", namespaceName, "versioningType", versioningType));
                tx.commit();
                return null;
            });

            // Test: Create a Class within the Namespace
            String className1 = "TestClass 1";
            String className2 = "TestClass 2";
            long order = 1;
            List<Double> position = List.of(1.0, 2.0);
            String comment = "Test comment";
            List<List<String>> descriptors = List.of(List.of("descriptor1", "String"));

            session.writeTransaction(tx -> {
                tx.run("CALL deposit.set.activeNamespace($namespaceName)", Map.of("namespaceName", namespaceName));
                tx.run("CALL deposit.create.class($className, $order, $position, $comment, $descriptors)",
                        Map.of(
                                "className", className1,
                                "order", order,
                                "position", position,
                                "comment", comment,
                                "descriptors", descriptors
                        ));
                tx.run("CALL deposit.create.class($className)", Map.of("className", className2));
                tx.commit();
                return null;
            });

            // Verify: Class nodes were created
            var result1 = session.run("MATCH (c:Class {name: $className}) RETURN c", Map.of("className", className1));
            assertTrue(result1.hasNext());
            var result2 = session.run("MATCH (c:Class {name: $className}) RETURN c", Map.of("className", className2));
            assertTrue(result2.hasNext());
        }
    }

    @Test
    public void shouldThrowExceptionIfClassAlreadyExists() {
        try (Session session = driver.session()) {
            // Setup: Create a Namespace to contain the Class
            String namespaceName = "TestNamespace";
            String className = "DuplicateClass";
            session.writeTransaction(tx -> {
                tx.run("CALL deposit.create.namespace($namespaceName, '', '')", Map.of("namespaceName", namespaceName));
                tx.run("CALL deposit.set.activeNamespace($namespaceName)", Map.of("namespaceName", namespaceName));
                tx.run("CALL deposit.create.class($className, -1, [], '', [])",
                        Map.of("className", className));
                tx.commit();
                return null;
            });

            // Try to create the same Class again, which should throw an exception
            try {
                session.writeTransaction(tx -> {
                    tx.run("CALL deposit.create.class($className, -1, [], '', [])",
                            Map.of("className", className));
                    tx.commit();
                    return null;
                });
                fail("Expected RuntimeException not thrown");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage().contains("Error creating Class. A Class with the name: " + className + " already exists in Namespace" + namespaceName + "."));
            }
        }
    }

    @Test
    public void shouldCreateClassNodeWithDefaultValues() {
        try (Session session = driver.session()) {
            // Setup: Create a Namespace to contain the Class
            String namespaceName = "TestNamespace";
            session.writeTransaction(tx -> {
                tx.run("CALL deposit.create.namespace($namespaceName, '', '')", Map.of("namespaceName", namespaceName));
                tx.run("CALL deposit.set.activeNamespace($namespaceName)", Map.of("namespaceName", namespaceName));
                tx.commit();
                return null;
            });

            // Test: Create a Class with default values
            String className = "DefaultClass";
            session.writeTransaction(tx -> {
                tx.run("CALL deposit.create.class($className)", Map.of("className", className));
                tx.commit();
                return null;
            });

            // Verify: Class node was created with default values
            var result = session.run("MATCH (c:Class {name: $className}) RETURN c.order as order, c.position as position, c.comment as comment", Map.of("className", className));
            assertTrue(result.hasNext());
            var record = result.next();
            assertEquals(-1L, record.get("order").asLong());
            var positionList = record.get("position").asList(Value::asDouble);
            assertTrue(positionList.isEmpty());
            assertEquals("", record.get("comment").asString());
        }
    }
}
