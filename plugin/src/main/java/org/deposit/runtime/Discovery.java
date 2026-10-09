package org.deposit.runtime;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;

/** The only public API supplied by the Stage 0 development artifact. */
public class Discovery {
    @org.neo4j.procedure.Context public org.neo4j.graphdb.GraphDatabaseService db;
    @Procedure(name = "deposit.get.info", mode = Mode.READ)
    @Description("Return the Deposit development version. No Deposit login is required.")
    public Stream<Info> info() {
        RuntimeReadiness.requireReady();
        var database=(org.neo4j.kernel.internal.GraphDatabaseAPI)db;
        deposit.common.ServerContext.forDatabase(database.databaseId().databaseId().uuid()).requireReady();
        Properties build = new Properties();
        try (var stream = Discovery.class.getResourceAsStream("/deposit-build.properties")) {
            if (stream == null) throw new IllegalStateException("Missing Deposit build metadata");
            build.load(stream);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read Deposit build metadata", e);
        }
        return Stream.of(new Info(build.getProperty("version")));
    }

    public static class Info {
        public final Map<String, Object> data;
        public final Map<String, Object> meta;
        public Info(String version) {
            data = Map.of("version", version);
            meta = new LinkedHashMap<>();
            meta.put("namespaceId", null);
            meta.put("revision", null);
            meta.put("operationId", null);
            meta.put("requestId", UUID.randomUUID().toString());
            meta.put("nextCursor", null);
        }
    }
}
