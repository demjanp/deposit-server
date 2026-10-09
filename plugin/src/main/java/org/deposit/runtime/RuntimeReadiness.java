package org.deposit.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

/** Fail closed before discovery declares the development plugin ready. */
public final class RuntimeReadiness {
    private RuntimeReadiness() {}
    public static void requireReady() {
        String file = System.getProperty("deposit.config");
        if (file == null) throw new IllegalStateException("Deposit configuration is required");
        Properties config = new Properties();
        try (var input = Files.newInputStream(Path.of(file))) {
            config.load(input);
            if (!config.stringPropertyNames().equals(Set.of("resourceRoot","exportRoot"))) {
                throw new IllegalStateException("Invalid Deposit configuration keys");
            }
            Path root = Path.of(config.getProperty("resourceRoot"));
            if (!root.isAbsolute() || !Files.isDirectory(root) || !Files.isWritable(root)) {
                throw new IllegalStateException("Deposit resourceRoot must be an absolute writable directory");
            }
            for (String directory : new String[]{"staging", "live", "deleted"}) {
                Path child = root.resolve(directory);
                if (!Files.isDirectory(child) || !Files.getFileStore(root).equals(Files.getFileStore(child))) {
                    throw new IllegalStateException("Deposit resource directories must share one filesystem");
                }
            }
            new deposit.common.ResourceFiles();
            Class.forName("org.neo4j.gis.spatial.SpatialDatabaseService");
            // Eagerly link the published index implementation. Merely finding the
            // service class misses superclass ABI changes until the first write.
            Class.forName("org.neo4j.gis.spatial.rtree.Envelope");
            Class.forName("org.neo4j.gis.spatial.index.LayerRTreeIndex");
            Class.forName("org.locationtech.jts.io.WKTReader");
        } catch (IOException | ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Deposit runtime dependencies or configuration are unavailable", e);
        }
    }
}
