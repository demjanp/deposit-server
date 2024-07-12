package deposit.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class DepositInfoTest {

    @BeforeEach
    public void setUp() throws Exception {
        resetDepositInfoVersion();
    }

    @AfterEach
    public void tearDown() throws IOException {
        Files.deleteIfExists(Paths.get("target/test-classes/version.properties"));
    }

    private void resetDepositInfoVersion() throws Exception {
        Field versionField = DepositInfo.class.getDeclaredField("version");
        versionField.setAccessible(true);
        versionField.set(null, null);

        Field initializedField = DepositInfo.class.getDeclaredField("initialized");
        initializedField.setAccessible(true);
        initializedField.set(null, false);
    }

    @Test
    public void versionIsLoadedFromPropertiesFile() throws IOException {
        // Create a temporary properties file
        Path tempPropertiesFile = Files.createTempFile("version", ".properties");
        Files.write(tempPropertiesFile, "git.commit.id.describe=2.0.0\n".getBytes());

        // Move the temporary properties file to the classpath
        Files.move(tempPropertiesFile, Paths.get("target/test-classes/version.properties"), StandardCopyOption.REPLACE_EXISTING);

        // Reload DepositInfo class
        DepositInfo.loadVersion();

        Assertions.assertEquals("2.0.0", DepositInfo.version);
    }

    @Test
    public void versionIsUnknownWhenPropertiesFileIsEmpty() throws IOException {
        // Create a temporary properties file with no content
        Path tempPropertiesFile = Files.createTempFile("version", ".properties");
        Files.write(tempPropertiesFile, "\n".getBytes());

        // Move the temporary properties file to the classpath
        Files.move(tempPropertiesFile, Paths.get("target/test-classes/version.properties"), StandardCopyOption.REPLACE_EXISTING);

        // Reload DepositInfo class
        DepositInfo.loadVersion();

        Assertions.assertEquals("unknown-version", DepositInfo.version);
    }

    @Test
    public void throwsExceptionWhenPropertiesFileNotFound() throws IOException {
        // Ensure the properties file does not exist
        Files.deleteIfExists(Paths.get("target/test-classes/version.properties"));

        // Reload DepositInfo class
        DepositInfo.loadVersion();

        Assertions.assertEquals("unknown-version", DepositInfo.version);
    }
}
