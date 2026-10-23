package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class DepositInfoTest {
    @Test void exposesTheResolvedBuildVersionEmbeddedInThePlugin() throws Exception {
        var version=DepositInfo.version();
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?"));
        var metadata=new Properties();
        try(var stream=DepositInfoTest.class.getResourceAsStream("/deposit-build.properties")) {
            assertNotNull(stream);
            metadata.load(stream);
        }
        assertEquals(version,metadata.getProperty("version"));
    }
}
