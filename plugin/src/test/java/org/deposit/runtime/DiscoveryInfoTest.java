package org.deposit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class DiscoveryInfoTest {
    @Test void advertisesOperationControlAndCanonicalExchange() {
        var info = new Discovery.Info("1.2.3","live-run");
        assertEquals("1.2.3", info.data.get("version"));
        assertEquals("live-run",info.data.get("incarnation"));
        assertEquals(java.util.List.of("fullNamespaceClone","pagedImportResults","operationControl","exchangeIntake"), info.data.get("capabilities"));
    }
}
