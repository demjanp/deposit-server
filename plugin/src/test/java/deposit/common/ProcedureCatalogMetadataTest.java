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
import java.util.Map;
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

    @Test void finalImportReadsMatchJavaSignaturesAndMetadata() throws Exception {
        var catalog = new ObjectMapper().readTree(Files.readString(repositoryRoot().resolve("runtime/public-api.json")));
        assertEquals("WRITE", find(catalog, "deposit.get.importPlan").path("mode").asText());
        for (String name : List.of("deposit.get.importResultPage", "deposit.get.cloneResultPage")) {
            JsonNode entry = find(catalog, name);
            assertEquals("Map<String,Object>", argument(entry, "cursor").path("type").asText());
            assertEquals("Long", argument(entry, "limit").path("type").asText());
            var method = java.util.Arrays.stream(deposit.api.DepositAPI.class.getDeclaredMethods())
                .filter(candidate -> candidate.isAnnotationPresent(org.neo4j.procedure.Procedure.class)
                    && name.equals(candidate.getAnnotation(org.neo4j.procedure.Procedure.class).name()))
                .findFirst().orElseThrow();
            assertEquals(Map.class, method.getParameters()[2].getType());
            assertEquals(Long.class, method.getParameters()[3].getType());
            assertEquals(org.neo4j.procedure.Mode.READ,
                method.getAnnotation(org.neo4j.procedure.Procedure.class).mode());
        }
        var method = java.util.Arrays.stream(deposit.api.DepositAPI.class.getDeclaredMethods())
            .filter(candidate -> candidate.isAnnotationPresent(org.neo4j.procedure.Procedure.class)
                && "deposit.get.importPlan".equals(candidate.getAnnotation(org.neo4j.procedure.Procedure.class).name()))
            .findFirst().orElseThrow();
        assertEquals(org.neo4j.procedure.Mode.WRITE, method.getAnnotation(org.neo4j.procedure.Procedure.class).mode());
        assertEquals(Boolean.class, method.getParameters()[2].getType());
        assertEquals("true", method.getParameters()[2].getAnnotation(org.neo4j.procedure.Name.class).defaultValue());
        assertEquals("Boolean", argument(find(catalog, "deposit.get.importPlan"), "renewLease").path("type").asText());
    }

    @Test void publicPageLimitIsAcceptedByNeo4jProcedureTypeChecker() throws Exception {
        var checker = new org.neo4j.procedure.impl.Cypher5TypeCheckers();
        for (String name : List.of("deposit.get.importResultPage", "deposit.get.cloneResultPage")) {
            var method = java.util.Arrays.stream(deposit.api.DepositAPI.class.getDeclaredMethods())
                .filter(candidate -> candidate.isAnnotationPresent(org.neo4j.procedure.Procedure.class)
                    && name.equals(candidate.getAnnotation(org.neo4j.procedure.Procedure.class).name()))
                .findFirst().orElseThrow();
            for (var parameter : method.getParameters()) {
                // Neo4j rejects java.lang.Integer but supports Long and primitive long.
                checker.converterFor(parameter.getParameterizedType());
            }
        }
    }

    private static JsonNode argument(JsonNode entry, String name) {
        for (JsonNode arg : entry.path("args")) if (name.equals(arg.path("name").asText())) return arg;
        fail("missing argument " + name + " in " + entry.path("name").asText());
        return null;
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
