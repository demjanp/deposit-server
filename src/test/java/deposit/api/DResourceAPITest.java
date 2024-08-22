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
            String originalFilename = "testFile.txt";
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
                assertFalse(node.get("isImage").asBoolean(), "isImage should be false.");

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
}
