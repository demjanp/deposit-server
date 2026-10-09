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

public class SetClassDescriptorOrderAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	@Test
	public void shouldSetClassDescriptorOrder() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace, Class, and a few Descriptors
			String namespaceName = "TestNamespace";
			String className = "TestClass";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.create.class($className, -1, [], '', [], $token)", Map.of("className", className, "token", token));
				tx.run("CALL deposit.create.classDescriptor($className, 'Descriptor1', 'String', 1, '', $token)", Map.of("className", className, "token", token));
				tx.run("CALL deposit.create.classDescriptor($className, 'Descriptor2', 'String', 2, '', $token)", Map.of("className", className, "token", token));
				tx.run("CALL deposit.create.classDescriptor($className, 'Descriptor3', 'String', 3, '', $token)", Map.of("className", className, "token", token));
				tx.commit();
				return null;
			});

			// Test: Change order of Descriptor2 to 3
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.set.classDescriptorOrder($className, 'Descriptor2', 3, $token)", Map.of("className", className, "token", token));
				tx.commit();
				return null;
			});

			// Verify: Descriptor2 should now have order 3, and Descriptor3 should have order 2
			var result1 = session.run("MATCH (d:Descriptor {name: 'Descriptor2'}) RETURN d.order as order");
			assertTrue(result1.hasNext());
			assertEquals(3L, result1.next().get("order").asLong());

			var result2 = session.run("MATCH (d:Descriptor {name: 'Descriptor3'}) RETURN d.order as order");
			assertTrue(result2.hasNext());
			assertEquals(2L, result2.next().get("order").asLong());
		}
	}

	@Test
	public void shouldAutoIncrementOrderWhenOrderIsMinusOne() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace, Class, and a couple of Descriptors
			String namespaceName = "TestNamespace";
			String className = "TestClass";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.create.class($className, -1, [], '', [], $token)", Map.of("className", className, "token", token));
				tx.run("CALL deposit.create.classDescriptor($className, 'Descriptor1', 'String', 1, '', $token)", Map.of("className", className, "token", token));
				tx.run("CALL deposit.create.classDescriptor($className, 'Descriptor2', 'String', 2, '', $token)", Map.of("className", className, "token", token));
				tx.commit();
				return null;
			});

			// Test: Set order of a new Descriptor with order = -1
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.classDescriptor($className, 'Descriptor3', 'String', -1, '', $token)", Map.of("className", className, "token", token));
				tx.commit();
				return null;
			});

			// Verify: Descriptor3 should have order 3
			var result = session.run("MATCH (d:Descriptor {name: 'Descriptor3'}) RETURN d.order as order");
			assertTrue(result.hasNext());
			assertEquals(3L, result.next().get("order").asLong());
		}
	}

	@Test
	public void shouldHandleMovingDescriptorToTopOrder() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace, Class, and Descriptors
			String namespaceName = "TestNamespace";
			String className = "TestClass";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.create.class($className, -1, [], '', [], $token)", Map.of("className", className, "token", token));
				tx.run("CALL deposit.create.classDescriptor($className, 'Descriptor1', 'String', 1, '', $token)", Map.of("className", className, "token", token));
				tx.run("CALL deposit.create.classDescriptor($className, 'Descriptor2', 'String', 2, '', $token)", Map.of("className", className, "token", token));
				tx.run("CALL deposit.create.classDescriptor($className, 'Descriptor3', 'String', 3, '', $token)", Map.of("className", className, "token", token));
				tx.commit();
				return null;
			});

			// Test: Move Descriptor3 to the top
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.set.classDescriptorOrder($className, 'Descriptor3', 1, $token)", Map.of("className", className, "token", token));
				tx.commit();
				return null;
			});

			// Verify: Descriptor3 should now have order 1, Descriptor1 should have order 2, and Descriptor2 should have order 3
			var result1 = session.run("MATCH (d:Descriptor {name: 'Descriptor3'}) RETURN d.order as order");
			assertTrue(result1.hasNext());
			assertEquals(1L, result1.next().get("order").asLong());

			var result2 = session.run("MATCH (d:Descriptor {name: 'Descriptor1'}) RETURN d.order as order");
			assertTrue(result2.hasNext());
			assertEquals(2L, result2.next().get("order").asLong());

			var result3 = session.run("MATCH (d:Descriptor {name: 'Descriptor2'}) RETURN d.order as order");
			assertTrue(result3.hasNext());
			assertEquals(3L, result3.next().get("order").asLong());
		}
	}

	@Test
	public void shouldHandleMovingDescriptorToBottomOrder() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace, Class, and Descriptors
			String namespaceName = "TestNamespace";
			String className = "TestClass";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.create.class($className, -1, [], '', [], $token)", Map.of("className", className, "token", token));
				tx.run("CALL deposit.create.classDescriptor($className, 'Descriptor1', 'String', 1, '', $token)", Map.of("className", className, "token", token));
				tx.run("CALL deposit.create.classDescriptor($className, 'Descriptor2', 'String', 2, '', $token)", Map.of("className", className, "token", token));
				tx.run("CALL deposit.create.classDescriptor($className, 'Descriptor3', 'String', 3, '', $token)", Map.of("className", className, "token", token));
				tx.commit();
				return null;
			});

			// Test: Move Descriptor1 to the bottom
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.set.classDescriptorOrder($className, 'Descriptor1', 3, $token)", Map.of("className", className, "token", token));
				tx.commit();
				return null;
			});

			// Verify: Descriptor1 should now have order 3, Descriptor2 should have order 1, and Descriptor3 should have order 2
			var result1 = session.run("MATCH (d:Descriptor {name: 'Descriptor1'}) RETURN d.order as order");
			assertTrue(result1.hasNext());
			assertEquals(3L, result1.next().get("order").asLong());

			var result2 = session.run("MATCH (d:Descriptor {name: 'Descriptor2'}) RETURN d.order as order");
			assertTrue(result2.hasNext());
			assertEquals(1L, result2.next().get("order").asLong());

			var result3 = session.run("MATCH (d:Descriptor {name: 'Descriptor3'}) RETURN d.order as order");
			assertTrue(result3.hasNext());
			assertEquals(2L, result3.next().get("order").asLong());
		}
	}

	@Test
	public void shouldThrowExceptionForInvalidDescriptorName() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace and a Class
			String namespaceName = "TestNamespace";
			String className = "TestClass";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.create.class($className, -1, [], '', [], $token)", Map.of("className", className, "token", token));
				tx.commit();
				return null;
			});

			// Test: Attempt to set order for a non-existent descriptor
			try {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.set.classDescriptorOrder($className, 'InvalidDescriptor', 1, $token)", Map.of("className", className, "token", token));
					tx.commit();
					return null;
				});
				fail("Expected RuntimeException not thrown");
			} catch (RuntimeException e) {
				assertTrue(e.getMessage().contains("Descriptor not found: InvalidDescriptor"));
			}
		}
	}
}
