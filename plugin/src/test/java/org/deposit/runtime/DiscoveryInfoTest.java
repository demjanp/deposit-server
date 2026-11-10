package org.deposit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class DiscoveryInfoTest {
    @Test void advertisesTheSupportedLazyImportResultProtocol() {
        var info = new Discovery.Info("1.2.3");
        assertEquals("1.2.3", info.data.get("version"));
        assertEquals(java.util.List.of(1), info.data.get("importResultFormats"));
    }
}
