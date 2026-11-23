package deposit.repository;

import static org.junit.jupiter.api.Assertions.*;

import deposit.common.ExceptionHandler;
import deposit.common.DepositExtension;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilderImplementation;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.ConstraintViolationException;
import org.junit.jupiter.api.Test;

class ResourceTransferRepositoryTest {
    private static final ResourceTransferRepository REPOSITORY = new ResourceTransferRepository(null, null);
    private static final String HASH = "0".repeat(64);
    @TempDir Path temporary;
    DatabaseManagementService management;

    @AfterEach void closeDatabase() { if (management != null) management.shutdown(); }

    @Test void beginChecksUnknownFieldsBeforeStrictScalarConversions() {
        var request = new LinkedHashMap<String,Object>();
        request.put("filename", null);request.put("bytes", null);request.put("sha256", HASH);request.put("mediaType", "x");request.put("unexpected", true);
        var failure = assertThrows(ExceptionHandler.DepositException.class, () -> REPOSITORY.begin(request));
        assertError(failure, "Unknown transfer field");
    }

    @Test void beginPreservesStrictStringAndIntegerValidationMessages() {
        var badFilename = new LinkedHashMap<String, Object>();
        badFilename.put("filename", null);badFilename.put("bytes", 0L);badFilename.put("sha256", HASH);badFilename.put("mediaType", "x");
        var stringFailure = assertThrows(ExceptionHandler.DepositException.class, () -> REPOSITORY.begin(badFilename));
        assertError(stringFailure, "Expected String");

        var badBytes = new LinkedHashMap<String, Object>();
        badBytes.put("filename", "x");badBytes.put("bytes", null);badBytes.put("sha256", HASH);badBytes.put("mediaType", "x");
        var integerFailure = assertThrows(ExceptionHandler.DepositException.class, () -> REPOSITORY.begin(badBytes));
        assertError(integerFailure, "Expected Integer");
    }

    @Test void uploadSizeValidationHasNoTwoGiBPolicyCeiling() {
        assertEquals(2L*1024*1024*1024+1, ResourceTransferRepository.validateUploadBytes(2L*1024*1024*1024+1));
        var failure=assertThrows(ExceptionHandler.DepositException.class,()->ResourceTransferRepository.validateUploadBytes(-1));
        assertTrue(failure.getMessage().contains("Upload size cannot be negative"));
    }

    private static void assertError(ExceptionHandler.DepositException failure, String message) {
        assertTrue(failure.getMessage().contains("\"code\":\"VALIDATION_FAILED\""), failure.getMessage());
        assertTrue(failure.getMessage().contains("\"message\":\"" + message + "\""), failure.getMessage());
    }
}
