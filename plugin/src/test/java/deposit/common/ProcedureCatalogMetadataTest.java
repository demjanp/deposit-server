package deposit.common;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcedureCatalogMetadataTest {
    private static Path repositoryRoot() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("runtime/public-api.json"))) root = root.getParent();
        assertNotNull(root, "server repository root was not found from the test working directory");
        return root;
    }

    @Test void publicCatalogMatchesProcedureCatalogScopeActionAndOrderedBindings() throws Exception {
        var catalog = new ObjectMapper().readTree(Files.readString(repositoryRoot().resolve("runtime/public-api.json")));
        var names = new HashSet<String>();
        for (JsonNode entry : catalog) {
            String name = entry.path("name").asText();
            assertTrue(names.add(name), "duplicate catalog procedure " + name);
            assertCatalogEntryMatchesImplementation(entry);
        }
        assertEquals(ProcedureCatalog.names(), names);
    }

    @Test void staleLoadClassificationFixturesAreRejected() throws Exception {
        var mapper = new ObjectMapper();
        var catalog = mapper.readTree(Files.readString(repositoryRoot().resolve("runtime/public-api.json")));
        for (String name : List.of("deposit.load.csv", "deposit.load.xlsx", "deposit.load.gpkg")) {
            JsonNode baseline = find(catalog, name);
            ObjectNode stale = baseline.deepCopy();
            stale.put("scope", "namespace");
            stale.put("action", false);
            assertNotEquals(baseline.get("scope"), stale.get("scope"));
            assertThrows(AssertionError.class, () -> assertCatalogEntryMatchesImplementation(stale), name);
        }
    }

    private static JsonNode find(JsonNode catalog, String name) {
        for (JsonNode entry : catalog) if (entry.path("name").asText().equals(name)) return entry;
        fail("missing catalog entry " + name);
        return null;
    }

    private static void assertCatalogEntryMatchesImplementation(JsonNode entry) {
        String name = entry.path("name").asText();
        var implementation = ProcedureCatalog.spec(name);
        assertEquals(entry.path("scope").asText(), implementation.scope(), name + " scope");
        assertEquals(entry.path("action").asBoolean(), implementation.action(), name + " action");
        var bindings = new ArrayList<String>();
        for (JsonNode arg : entry.path("args")) bindings.add(arg.path("binding").asText());
        assertEquals(bindings, implementation.bindings(), name + " ordered bindings");
    }
}
