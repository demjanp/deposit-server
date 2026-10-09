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

public class GetClassDescriptorAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	private String createNamespaceAndClass(Session session, String namespaceName, String className) {
		return session.writeTransaction(tx -> {
			tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
			tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));
			tx.run("CALL deposit.create.class($className, -1, [], '', [], $token)", Map.of("className", className, "token", token));
			tx.commit();
			return className;
		});
	}

	@Test
	public void shouldGetClassDescriptorByName() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String className = "TestClass";

			createNamespaceAndClass(session, namespaceName, className);

			// Create a class descriptor
			String descriptorName = "TestClassDescriptor";
			createClassDescriptor(session, className, descriptorName, "String", 1, "Test descriptor comment", token);

			// Retrieve the class descriptor using the procedure
			session.readTransaction(tx -> {
				Result result = tx.run("CALL deposit.get.classDescriptor($className, $descriptorName, $token) YIELD node RETURN node",
						Map.of("className", className, "descriptorName", descriptorName, "token", token));

				assertTrue("Class Descriptor node should be retrieved", result.hasNext());
				tx.commit();
				return null;
			});
		}
	}

	@Test
	public void shouldGetClassDescriptorById() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String className = "TestClass";

			createNamespaceAndClass(session, namespaceName, className);

			// Create a class descriptor
			String descriptorName = "TestClassDescriptor";
			String descriptorId = createClassDescriptor(session, className, descriptorName, "String", 1, "Test descriptor comment", token);

			// Retrieve the class descriptor using the procedure
			session.readTransaction(tx -> {
				Result result = tx.run("CALL deposit.get.classDescriptorById($descriptorId, $token) YIELD node RETURN node",
						Map.of("descriptorId", descriptorId, "token", token));

				assertTrue("Class Descriptor node should be retrieved", result.hasNext());
				tx.commit();
				return null;
			});
		}
	}

	@Test
	public void shouldGetAllClassDescriptorsForClass() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String className = "TestClass";

			createNamespaceAndClass(session, namespaceName, className);

			// Create multiple class descriptors
			String descriptorName1 = "ClassDescriptor1";
			String descriptorName2 = "ClassDescriptor2";
			createClassDescriptor(session, className, descriptorName1, "String", 1, "Comment1", token);
			createClassDescriptor(session, className, descriptorName2, "String", 2, "Comment2", token);

			// Retrieve all class descriptors for the class using the procedure
			session.readTransaction(tx -> {
				Result result = tx.run("CALL deposit.get.classDescriptor($className, '', $token) YIELD node RETURN node",
						Map.of("className", className, "token", token));

				int count = 0;
				while (result.hasNext()) {
					result.next();
					count++;
				}

				assertTrue("Should retrieve both class descriptors", count == 2);
				tx.commit();
				return null;
			});
		}
	}
}
