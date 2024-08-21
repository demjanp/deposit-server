package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.neo4j.driver.Session;
import org.neo4j.driver.exceptions.ClientException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.*;

public class UtilsResourceAPITest extends BaseNeo4jTest {

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
    public void shouldStoreFileAndReturnCorrectNode() {
        try (Session session = driver.session()) {
            String originalFilename = "testFile.txt";
            String content = "This is a test file.";
            String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

            session.writeTransaction(tx -> {
                var result = tx.run("CALL deposit.utils.resource($stream, $filename, $token) YIELD node",
                        Map.of("stream", base64Content, "filename", originalFilename, "token", token));
                assertTrue("Result should contain a node.", result.hasNext());

                var node = result.single().get("node").asNode();
                assertEquals("DResource", node.labels().iterator().next(), "Node should have the label 'DResource'.");
                assertEquals(originalFilename, node.get("filename").asString(), "Filename should match.");
                assertNotNull(node.get("storedFilename").asString(), "Stored filename should not be null.");
                assertFalse(node.get("isImage").asBoolean(), "isImage should be false.");

                // Check that the file was created on disk
                Path storedFilePath = tempFolder.getRoot().toPath().resolve("deposit_transient_storage").resolve(node.get("storedFilename").asString());
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
                    tx.run("CALL deposit.utils.resource($stream, $filename, $token)",
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
                    tx.run("CALL deposit.utils.resource($stream, $filename, $token)",
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
                    tx.run("CALL deposit.utils.resource($stream, $filename, $token)",
                            Map.of("stream", base64Content, "filename", longFilename, "token", token));
                    return null;
                });
            });

            assertTrue("Exception should mention filename length.",
					exception.getMessage().contains("Filename exceeds the maximum length"));
        }
    }
}
