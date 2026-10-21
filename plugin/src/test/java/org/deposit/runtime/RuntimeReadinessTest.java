package org.deposit.runtime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeReadinessTest {
    @TempDir Path directory;

    @Test void secretRootIsRequiredByCurrentRuntimeConfiguration() throws Exception {
        Path config=directory.resolve("deposit.properties");
        Files.writeString(config,"resourceRoot="+directory.toAbsolutePath()+"\n"
            +"exportRoot="+directory.toAbsolutePath()+"\n"
            +"download.listenAddress=127.0.0.1\n"
            +"download.port=17770\n"
            +"download.publicBaseUrl=http://127.0.0.1:17770\n");
        String previous=System.getProperty("deposit.config");
        try {
            System.setProperty("deposit.config",config.toString());
            IllegalStateException failure=assertThrows(IllegalStateException.class,RuntimeReadiness::requireReady);
            assertTrue(failure.getMessage().contains("configuration keys"));
        } finally {
            if(previous==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previous);
        }
    }
}
