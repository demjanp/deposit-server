package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;

import java.io.IOException;
import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertEquals;

public class CreateClassDescriptorAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	@Test
	public void shouldCreateClassDescriptorNode() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace and a Class to contain the Descriptor
			String namespaceName = "TestNamespace";
			String className = "TestClass";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.create.class($className, -1, [], '', [], $token)", Map.of("className", className, "token", token));
				tx.commit();
				return null;
			});

			// Test: Create a Class Descriptor within the Class
			String descriptorName = "TestDescriptor";
			String type = "String";
			long order = 1;
			String comment = "Test descriptor comment";

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.classDescriptor($className, $descriptorName, $type, $order, $comment, $token)",
						Map.of(
								"className", className,
								"descriptorName", descriptorName,
								"type", type,
								"order", order,
								"comment", comment,
								"token", token
						));
				tx.commit();
				return null;
			});

			// Verify: Descriptor node was created
			Result result = session.run("MATCH (d:Descriptor {name: $descriptorName}) RETURN d", Map.of("descriptorName", descriptorName));
			assertTrue(result.hasNext());
		}
	}

	@Test
	public void shouldThrowExceptionIfDescriptorAlreadyExists() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace, Class, and a Descriptor
			String namespaceName = "TestNamespace";
			String className = "TestClass";
			String descriptorName = "DuplicateDescriptor";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', '', $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.create.class($className, -1, [], '', [], $token)", Map.of("className", className, "token", token));
				tx.run("CALL deposit.create.classDescriptor($className, $descriptorName, 'String', -1, '', $token)",
						Map.of("className", className, "descriptorName", descriptorName, "token", token));
				tx.commit();
				return null;
			});

			// Test: Try to create the same Descriptor again, which should throw an exception
			try {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.create.classDescriptor($className, $descriptorName, 'String', -1, '', $token)",
							Map.of("className", className, "descriptorName", descriptorName, "token", token));
					tx.commit();
					return null;
				});
				fail("Expected RuntimeException not thrown");
			} catch (RuntimeException e) {
				assertTrue(e.getMessage().contains("Error creating Class Descriptor. A Descriptor with the name: " + descriptorName + " already exists for Class" + className + "."));
			}
		}
	}

	@Test
	public void shouldCreateClassDescriptorWithDefaultValues() {
		try (Session session = driver.session()) {
			// Setup: Create a Namespace and a Class to contain the Descriptor
			String namespaceName = "TestNamespace";
			String className = "TestClass";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', '', $token)", Map.of("namespaceName", namespaceName, "token", token));
				ServerContext.setActiveNamespace(namespaceName, token);
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.create.class($className, -1, [], '', [], $token)", Map.of("className", className, "token", token));
				tx.commit();
				return null;
			});

			// Test: Create a Descriptor with default values
			String descriptorName = "DefaultDescriptor";

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.classDescriptor($className, $descriptorName, 'String', -1, '', $token)",
						Map.of("className", className, "descriptorName", descriptorName, "token", token));
				tx.commit();
				return null;
			});

			// Verify: Descriptor node was created with default values
			var result = session.run("MATCH (d:Descriptor {name: $descriptorName}) RETURN d.order as order, d.comment as comment", Map.of("descriptorName", descriptorName));
			assertTrue(result.hasNext());
			var record = result.next();
			assertEquals(-1L, record.get("order").asLong());
			assertEquals("", record.get("comment").asString());
		}
	}
}
