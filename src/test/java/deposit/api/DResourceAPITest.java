package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.BeforeClass;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.neo4j.driver.exceptions.ClientException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.*;

public class DResourceAPITest extends BaseNeo4jTest {

	private static String token;

	public static String homeDir;


	@BeforeClass
	public static void setUpClass() throws Exception {
		token = ServerContext.getInstance().generateToken("testUser");
		homeDir = System.getProperty("java.io.tmpdir").toString() + "Deposit_Testing";
		System.setProperty("user.home", homeDir);
	}

	private static String generateHashBasedSubdir(String filename) {
		int hash = filename.hashCode();
		return Integer.toHexString(hash).substring(0, 2);  // Using first 2 hex characters
	}

	@Test
	public void shouldStoreFileAndReturnCorrectNode() {
		try (Session session = driver.session()) {
			String originalFilename = "testFile.jpg";
			String content = "This is a test file.";
			String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

			session.writeTransaction(tx -> {
				var result = tx.run("CALL deposit.utils.upload($stream, $filename, $token) YIELD node",
						Map.of("stream", base64Content, "filename", originalFilename, "token", token));
				assertTrue("Result should contain a node.", result.hasNext());

				var node = result.single().get("node").asNode();
				assertEquals("DResource", node.labels().iterator().next(), "Node should have the label 'DResource'.");
				assertEquals(originalFilename, node.get("filename").asString(), "Filename should match.");
				assertNotNull(node.get("storedFilename").asString(), "Stored filename should not be null.");
				assertEquals("JPEG", node.get("imageFormat").asString(), "imageFormat should be JPEG.");

				// Check that the file was created on disk
				Path storedFilePath = Path.of(homeDir).resolve("Deposit/transient_storage").resolve(node.get("storedFilename").asString());
				assertTrue("The file should be created on disk.", Files.exists(storedFilePath));

				return null;
			});
		}
	}

	@Test
	public void shouldFailForInvalidFilename() {
		try (Session session = driver.session()) {
			String base64Content = Base64.getEncoder().encodeToString("content".getBytes());
			String invalidFilename = "invalid<>filename.txt";

			ClientException exception = assertThrows(ClientException.class, () -> {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.utils.upload($stream, $filename, $token)",
							Map.of("stream", base64Content, "filename", invalidFilename, "token", token));
					return null;
				});
			});

			assertTrue("Exception should mention invalid characters.",
					exception.getMessage().contains("Filename contains invalid characters"));
		}
	}

	@Test
	public void shouldFailForReservedWindowsFilename() {
		try (Session session = driver.session()) {
			String base64Content = Base64.getEncoder().encodeToString("content".getBytes());
			String reservedFilename = "CON";

			ClientException exception = assertThrows(ClientException.class, () -> {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.utils.upload($stream, $filename, $token)",
							Map.of("stream", base64Content, "filename", reservedFilename, "token", token));
					return null;
				});
			});

			assertTrue("Exception should mention reserved Windows filename.",
					exception.getMessage().contains("Filename is a reserved name under Windows"));
		}
	}

	@Test
	public void shouldFailForFilenameExceedingMaxLength() {
		try (Session session = driver.session()) {
			String base64Content = Base64.getEncoder().encodeToString("content".getBytes());
			String longFilename = "a".repeat(256) + ".txt";

			ClientException exception = assertThrows(ClientException.class, () -> {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.utils.upload($stream, $filename, $token)",
							Map.of("stream", base64Content, "filename", longFilename, "token", token));
					return null;
				});
			});

			assertTrue("Exception should mention filename length.",
					exception.getMessage().contains("Filename exceeds the maximum length"));
		}
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

				Result result = tx.run("CALL deposit.create.object($class, [], [], $token) YIELD node RETURN node.id AS objectId",
									   Map.of("class", objectClass, "token", token));
				return result.single().get("objectId").asString();
			});

			// Step 2: Execute the query in one transaction
			String storedFilename = session.writeTransaction(tx -> {
				Result result = tx.run("""
					CALL deposit.utils.upload($stream, $filename, $token) YIELD node AS resource
					WITH resource
					CALL deposit.create.descriptor($objectId, $descriptorName, resource, $token) YIELD node AS descriptor
					RETURN descriptor, resource
					""",
					Map.of(
						"stream", base64Content,
						"filename", originalFilename,
						"objectId", objectId,
						"descriptorName", descriptorName,
						"token", token
					)
				);

				assertTrue("Result should contain both descriptor and resource nodes.", result.hasNext());

				var record = result.single();
				var descriptor = record.get("descriptor").asNode();
				var resource = record.get("resource").asNode();

				assertEquals(descriptorName, descriptor.get("name").asString(), "Descriptor name should match.");
				assertEquals(originalFilename, descriptor.get("value").asString(), "Descriptor value should match filename.");

				return resource.get("storedFilename").asString();
			});

			// Step 3: Verify that the file was created on disk
			Path storedFilePath = Path.of(homeDir).resolve("Deposit/permanent_storage/" + generateHashBasedSubdir(storedFilename) + "/" + storedFilename);
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

	@Test
	public void shouldDownloadFileSuccessfully() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String objectClass = "TestClass";
			String descriptorName = "TestDescriptor";
			String originalFilename = "testDownloadFile.txt";
			String content = "This is a test file for download.";
			String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

			// Execute all operations in a single transaction for setup
			String objectId = session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)",
						Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)",
						Map.of("namespaceName", namespaceName, "token", token));

				String returnedObjectId = tx.run("CALL deposit.create.object($class, [], [], $token) YIELD node RETURN node.id AS objectId",
						Map.of("class", objectClass, "token", token))
						.single().get("objectId").asString();

				tx.run("""
					CALL deposit.utils.upload($stream, $filename, $token) YIELD node AS resource
					WITH resource
					CALL deposit.create.descriptor($objectId, $descriptorName, resource, $token) YIELD node AS descriptor
					RETURN resource.storedFilename AS storedFilename
					""",
					Map.of(
						"stream", base64Content,
						"filename", originalFilename,
						"descriptorName", descriptorName,
						"objectId", returnedObjectId,
						"token", token
					)
				);

				return returnedObjectId;
			});

			// Fetch the descriptor and download the file in a separate transaction
			String downloadedContent = session.writeTransaction(tx -> {
				// Download the file using the fetched descriptor node
				Result result = tx.run("""
						CALL deposit.get.descriptor($objectId, $descriptorName, $token) YIELD node AS descriptor
						CALL deposit.utils.download(descriptor, $token) YIELD stream
						RETURN stream
						""",
						Map.of(
						"objectId", objectId,
						"descriptorName", descriptorName,
						"token", token
						));
				assertTrue("Download result should have content.", result.hasNext());
				return result.single().get("stream").asString();
			});

			// Validate the downloaded content
			String decodedContent = new String(Base64.getDecoder().decode(downloadedContent));
			assertEquals(content, decodedContent, "Downloaded content should match the original content.");
		}
	}

	@Test
	public void shouldDownloadFileUsingDescriptorId() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String objectClass = "TestClass";
			String descriptorName = "TestDescriptor";
			String originalFilename = "testDownloadById.txt";
			String content = "This is a test file for download by ID.";
			String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

			// Execute all operations in a single transaction
			String descriptorId = session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)",
						Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)",
						Map.of("namespaceName", namespaceName, "token", token));

				String objectId = tx.run("CALL deposit.create.object($class, [], [], $token) YIELD node RETURN node.id AS objectId",
						Map.of("class", objectClass, "token", token))
						.single().get("objectId").asString();

				Result result = tx.run("""
					CALL deposit.utils.upload($stream, $filename, $token) YIELD node AS resource
					WITH resource
					CALL deposit.create.descriptor($objectId, $descriptorName, resource, $token) YIELD node AS descriptor
					RETURN descriptor.id AS descriptorId
					""",
					Map.of(
						"stream", base64Content,
						"filename", originalFilename,
						"descriptorName", descriptorName,
						"objectId", objectId,
						"token", token
					)
				);

				return result.single().get("descriptorId").asString();
			});

			// Download the file using the descriptor ID in a separate transaction
			String downloadedContent = session.writeTransaction(tx -> {
				Result result = tx.run("CALL deposit.utils.download($descriptor, $token) YIELD stream",
						Map.of("descriptor", descriptorId, "token", token));
				assertTrue("Download result should have content.", result.hasNext());
				return result.single().get("stream").asString();
			});

			// Validate the downloaded content
			String decodedContent = new String(Base64.getDecoder().decode(downloadedContent));
			assertEquals(content, decodedContent, "Downloaded content should match the original content.");
		}
	}

	@Test
	public void shouldFailDownloadForDescriptorWithStringValue() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String objectClass = "TestClass";
			String descriptorName = "TestDescriptor";
			String stringValue = "This is a string value, not a resource.";

			// Execute all operations in a single transaction
			String descriptorId = session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)",
						Map.of("namespaceName", namespaceName, "token", token));
				tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)",
						Map.of("namespaceName", namespaceName, "token", token));

				String objectId = tx.run("CALL deposit.create.object($class, [], [], $token) YIELD node RETURN node.id AS objectId",
						Map.of("class", objectClass, "token", token))
						.single().get("objectId").asString();

				Result result = tx.run("CALL deposit.create.descriptor($objectId, $descriptorName, $value, $token) YIELD node AS descriptor RETURN descriptor.id AS descriptorId",
						Map.of(
							"descriptorName", descriptorName,
							"value", stringValue,
							"objectId", objectId,
							"token", token
						)
				);

				return result.single().get("descriptorId").asString();
			});

			// Attempt to download from the descriptor with a string value in a separate transaction
			RuntimeException exception = assertThrows(RuntimeException.class, () -> {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.utils.download($descriptor, $token) YIELD stream",
							Map.of("descriptor", descriptorId, "token", token));
					return null;
				});
			});

			// Validate the exception message
			assertTrue("Exception should mention No result found for descriptor:.",
					exception.getMessage().contains("No result found for descriptor:"));
		}
	}
}
