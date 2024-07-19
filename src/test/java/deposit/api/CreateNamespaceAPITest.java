package deposit.api;

import deposit.BaseNeo4jTest;
import org.junit.Test;
import org.neo4j.driver.Session;

import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class CreateNamespaceAPITest extends BaseNeo4jTest {

	@Override
	protected Class<?> getProcedureClass() {
		return CreateNamespaceAPI.class;
	}

	@Test
	public void shouldCreateNamespaceNode() {
		try (Session session = driver.session()) {
			String name = "TestNamespace";
			String description = "This is a test namespace.";
			String versioningType = "persistent";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($name, $description, $versioningType)",
						Map.of("name", name, "description", description, "versioningType", versioningType));
				tx.commit();
				return null;
			});
			var result = session.run("MATCH (n:Namespace {name: $name}) RETURN n", Map.of("name", name));
			assertTrue(result.hasNext());
		}
	}

	@Test
	public void shouldThrowExceptionIfNamespaceAlreadyExists() {
		try (Session session = driver.session()) {
			String name = "DuplicateNamespace";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($name, '', '')", Map.of("name", name));
				tx.commit();
				return null;
			});
			// Try to create the same namespace again, which should throw an exception
			try {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.create.namespace($name, '', '')", Map.of("name", name));
					tx.commit();
					return null;
				});
				fail("Expected RuntimeException not thrown");
			} catch (RuntimeException e) {
				assertTrue(e.getMessage().contains("Error creating Namespace. A namespace with the name: " + name + " already exists."));
			}
		}
	}

	@Test
	public void shouldCreateNamespaceNodeWithDefaultValues() {
		try (Session session = driver.session()) {
			String name = "DefaultNamespace";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($name)", Map.of("name", name));
				tx.commit();
				return null;
			});
			var result = session.run("MATCH (n:Namespace {name: $name}) RETURN n.description as description, n.versioningType as versioningType", Map.of("name", name));
			assertTrue(result.hasNext());
			var record = result.next();
			assertEquals("", record.get("description").asString());
			assertEquals("", record.get("versioningType").asString());
		}
	}
}
