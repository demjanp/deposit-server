package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.*;

public class CreateDescriptorWithResourceAPITest extends BaseNeo4jTest {

	private String token;

	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();  // JUnit 4 TemporaryFolder rule

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
		System.setProperty("user.home", tempFolder.getRoot().getAbsolutePath());
	}

	@Test
	public void shouldCreateDescriptorWithResource() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String objectClass = "TestClass";
			String descriptorName = "TestDescriptor";
			String originalFilename = "testFile.txt";
			String content = "This is a test file.";
			String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

			// Step 1: Create Namespace and Object (like in shouldCreateDescriptorWithVariousValueTypes)
			String objectId = session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", 
					   Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", 
					   Map.of("namespaceName", namespaceName, "token", token));

				Result result = tx.run("CALL deposit.create.object([$class], [], [], $token) YIELD node RETURN node.id AS objectId",
									   Map.of("class", objectClass, "token", token));
				return result.single().get("objectId").asString();
			});

			// Step 2: Execute the query in one transaction
			session.writeTransaction(tx -> {
				Result result = tx.run("""
					CALL deposit.utils.resource($stream, $filename, $token) YIELD node AS resource
					CALL deposit.create.descriptor($objectId, $descriptorName, resource, $token) YIELD node AS descriptor
					RETURN descriptor
					""", 
					Map.of(
						"stream", base64Content, 
						"filename", originalFilename, 
						"objectId", objectId, 
						"descriptorName", descriptorName, 
						"token", token
					)
				);

				assertTrue("Result should contain a descriptor node.", result.hasNext());

				var descriptor = result.single().get("descriptor").asNode();
				assertEquals(descriptorName, descriptor.get("name").asString(), "Descriptor name should match.");
				assertEquals(originalFilename, descriptor.get("value").asString(), "Descriptor value should match filename.");

				return null;
			});

			// Step 3: Verify that the file was created on disk
			Path storedFilePath = tempFolder.getRoot().toPath().resolve("deposit_transient_storage");
			assertTrue("The file should be created on disk.", Files.exists(storedFilePath));

			// Step 4: Verify the database nodes and relationships
			session.readTransaction(tx -> {
				Result result = tx.run("""
					MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object)-[:HAS_DESCRIPTOR]->(d:Descriptor {name: $descriptorName, value: $filename})-[:HAS_RESOURCE]->(resource:DResource {filename: $filename})
					RETURN d, resource
					""", 
					Map.of(
						"namespaceName", namespaceName, 
						"descriptorName", descriptorName, 
						"filename", originalFilename
					)
				);

				assertTrue("Database should contain the expected Descriptor and Resource nodes with correct relationships.", result.hasNext());
				return null;
			});
		}
	}
}
