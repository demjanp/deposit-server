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
            var result1 = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: $className}) RETURN c", Map.of("namespaceName", namespaceName, "className", className1));
            assertTrue(result1.hasNext());
            var result2 = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: $className}) RETURN c", Map.of("namespaceName", namespaceName, "className", className2));
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
            var result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: $className}) RETURN c.order as order, c.position as position, c.comment as comment", Map.of("namespaceName", namespaceName, "className", className));
            assertTrue(result.hasNext());
            var record = result.next();
            assertEquals(1L, record.get("order").asLong());
            var positionList = record.get("position").asList(Value::asDouble);
            assertTrue(positionList.isEmpty());
            assertEquals("", record.get("comment").asString());
        }
    }

    @Test
    public void shouldSetClassComment() {
        try (Session session = driver.session()) {
            // Setup: Create a Namespace and a Class
            String namespaceName = "TestNamespace";
            String className = "TestClass";
            String initialComment = "Initial comment";
            String newComment = "Updated comment";
            session.writeTransaction(tx -> {
                tx.run("CALL deposit.create.namespace($namespaceName, '', '')", Map.of("namespaceName", namespaceName));
                tx.run("CALL deposit.set.activeNamespace($namespaceName)", Map.of("namespaceName", namespaceName));
                tx.run("CALL deposit.create.class($className, -1, [], $initialComment, [])",
                        Map.of("className", className, "initialComment", initialComment));
                tx.commit();
                return null;
            });

            // Test: Update the Class comment
            session.writeTransaction(tx -> {
                tx.run("CALL deposit.set.classComment($className, $newComment, '')",
                        Map.of("className", className, "newComment", newComment));
                tx.commit();
                return null;
            });

            // Verify: Class comment was updated
            var result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: $className}) RETURN c.comment as comment", Map.of("namespaceName", namespaceName, "className", className));
            assertTrue(result.hasNext());
            var record = result.next();
            assertEquals(newComment, record.get("comment").asString());
        }
    }

    @Test
	public void shouldCreateClassWithCorrectOrderWhenOrderIsMinusOne() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace and a couple of Class nodes
			String namespaceName = "TestNamespace";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', '')", Map.of("namespaceName", namespaceName));
				tx.run("CALL deposit.set.activeNamespace($namespaceName)", Map.of("namespaceName", namespaceName));
				tx.run("CALL deposit.create.class($className, 1, [], '', [])", Map.of("className", "FirstClass"));
				tx.run("CALL deposit.create.class($className, 2, [], '', [])", Map.of("className", "SecondClass"));
				tx.commit();
				return null;
			});

			// Test: Create a new Class with order = -1
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.class($className, -1, [], '', [])", Map.of("className", "ThirdClass"));
				tx.commit();
				return null;
			});

			// Verify: The new class should have order = 3
			var result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: $className}) RETURN c.order as order", Map.of("namespaceName", namespaceName, "className", "ThirdClass"));
			assertTrue(result.hasNext());
			var record = result.next();
			assertEquals(3L, record.get("order").asLong());
		}
	}

	@Test
	public void shouldIncrementOrderOfExistingClassesWhenOrderIsSpecified() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace and a couple of Class nodes
			String namespaceName = "TestNamespace";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', '')", Map.of("namespaceName", namespaceName));
				tx.run("CALL deposit.set.activeNamespace($namespaceName)", Map.of("namespaceName", namespaceName));
				tx.run("CALL deposit.create.class($className, 1, [], '', [])", Map.of("className", "FirstClass"));
				tx.run("CALL deposit.create.class($className, 2, [], '', [])", Map.of("className", "SecondClass"));
				tx.commit();
				return null;
			});

			// Test: Create a new Class with order = 1
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.class($className, 1, [], '', [])", Map.of("className", "NewFirstClass"));
				tx.commit();
				return null;
			});

			// Verify: Orders of existing classes should have been incremented
			var result1 = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: 'NewFirstClass'}) RETURN c.order as order", Map.of("namespaceName", namespaceName));
			assertTrue(result1.hasNext());
			assertEquals(1L, result1.next().get("order").asLong());

			var result2 = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: 'FirstClass'}) RETURN c.order as order", Map.of("namespaceName", namespaceName));
			assertTrue(result2.hasNext());
			assertEquals(2L, result2.next().get("order").asLong());

			var result3 = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: 'SecondClass'}) RETURN c.order as order", Map.of("namespaceName", namespaceName));
			assertTrue(result3.hasNext());
			assertEquals(3L, result3.next().get("order").asLong());
		}
	}
}
