package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;

import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class SetClassOrderAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	@Test
	public void shouldSetClassOrder() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace and a few Classes
			String namespaceName = "TestNamespace";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.create.class('Class1', 1, [], '', [], $token)", Map.of("token", token));
				tx.run("CALL deposit.create.class('Class2', 2, [], '', [], $token)", Map.of("token", token));
				tx.run("CALL deposit.create.class('Class3', 3, [], '', [], $token)", Map.of("token", token));
				tx.commit();
				return null;
			});

			// Test: Change order of Class2 to 3
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.set.classOrder('Class2', 3, $token)", Map.of("token", token));
				tx.commit();
				return null;
			});

			// Verify: Class2 should now have order 3, and Class3 should have order 2
			var result1 = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: 'Class2'}) RETURN c.order as order", Map.of("namespaceName", namespaceName));
			assertTrue(result1.hasNext());
			assertEquals(3L, result1.next().get("order").asLong());

			var result2 = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: 'Class3'}) RETURN c.order as order", Map.of("namespaceName", namespaceName));
			assertTrue(result2.hasNext());
			assertEquals(2L, result2.next().get("order").asLong());
		}
	}

	@Test
	public void shouldAutoIncrementOrderWhenOrderIsMinusOne() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace and a few Classes
			String namespaceName = "TestNamespace";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.create.class('Class1', 1, [], '', [], $token)", Map.of("token", token));
				tx.run("CALL deposit.create.class('Class2', 2, [], '', [], $token)", Map.of("token", token));
				tx.commit();
				return null;
			});

			// Test: Set order of a new Class with order = -1
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.class('Class3', -1, [], '', [], $token)", Map.of("token", token));
				tx.commit();
				return null;
			});

			// Verify: Class3 should have order 3
			var result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: 'Class3'}) RETURN c.order as order", Map.of("namespaceName", namespaceName));
			assertTrue(result.hasNext());
			assertEquals(3L, result.next().get("order").asLong());
		}
	}

	@Test
	public void shouldHandleMovingClassToTopOrder() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace and a few Classes
			String namespaceName = "TestNamespace";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.create.class('Class1', 1, [], '', [], $token)", Map.of("token", token));
				tx.run("CALL deposit.create.class('Class2', 2, [], '', [], $token)", Map.of("token", token));
				tx.run("CALL deposit.create.class('Class3', 3, [], '', [], $token)", Map.of("token", token));
				tx.commit();
				return null;
			});

			// Test: Move Class3 to the top
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.set.classOrder('Class3', 1, $token)", Map.of("token", token));
				tx.commit();
				return null;
			});

			// Verify: Class3 should now have order 1, Class1 should have order 2, and Class2 should have order 3
			var result1 = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: 'Class3'}) RETURN c.order as order", Map.of("namespaceName", namespaceName));
			assertTrue(result1.hasNext());
			assertEquals(1L, result1.next().get("order").asLong());

			var result2 = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: 'Class1'}) RETURN c.order as order", Map.of("namespaceName", namespaceName));
			assertTrue(result2.hasNext());
			assertEquals(2L, result2.next().get("order").asLong());

			var result3 = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: 'Class2'}) RETURN c.order as order", Map.of("namespaceName", namespaceName));
			assertTrue(result3.hasNext());
			assertEquals(3L, result3.next().get("order").asLong());
		}
	}

	@Test
	public void shouldHandleMovingClassToBottomOrder() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace and a few Classes
			String namespaceName = "TestNamespace";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.create.class('Class1', 1, [], '', [], $token)", Map.of("token", token));
				tx.run("CALL deposit.create.class('Class2', 2, [], '', [], $token)", Map.of("token", token));
				tx.run("CALL deposit.create.class('Class3', 3, [], '', [], $token)", Map.of("token", token));
				tx.commit();
				return null;
			});

			// Test: Move Class1 to the bottom
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.set.classOrder('Class1', 3, $token)", Map.of("token", token));
				tx.commit();
				return null;
			});

			// Verify: Class1 should now have order 3, Class2 should have order 1, and Class3 should have order 2
			var result1 = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: 'Class1'}) RETURN c.order as order", Map.of("namespaceName", namespaceName));
			assertTrue(result1.hasNext());
			assertEquals(3L, result1.next().get("order").asLong());

			var result2 = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: 'Class2'}) RETURN c.order as order", Map.of("namespaceName", namespaceName));
			assertTrue(result2.hasNext());
			assertEquals(1L, result2.next().get("order").asLong());

			var result3 = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(c:Class {name: 'Class3'}) RETURN c.order as order", Map.of("namespaceName", namespaceName));
			assertTrue(result3.hasNext());
			assertEquals(2L, result3.next().get("order").asLong());
		}
	}

	@Test
	public void shouldThrowExceptionForInvalidClassName() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace and a Class
			String namespaceName = "TestNamespace";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.create.class('ValidClass', 1, [], '', [], $token)", Map.of("token", token));
				tx.commit();
				return null;
			});

			// Test: Attempt to set order for a non-existent class
			try {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.set.classOrder('InvalidClass', 1, $token)", Map.of("token", token));
					tx.commit();
					return null;
				});
				fail("Expected RuntimeException not thrown");
			} catch (RuntimeException e) {
				assertTrue(e.getMessage().contains("Class not found: InvalidClass"));
			}
		}
	}
}
