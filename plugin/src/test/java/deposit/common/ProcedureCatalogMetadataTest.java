package deposit.common;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import deposit.api.DepositAPI;
import deposit.service.DomainService;
import org.junit.jupiter.api.Test;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;
import org.deposit.runtime.Discovery;

class ProcedureCatalogMetadataTest {
    private static Path repositoryRoot() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("runtime/public-api.json"))) root = root.getParent();
        assertNotNull(root, "server repository root was not found from the test working directory");
        return root;
    }

    @Test void publicCatalogMatchesProcedureCatalogScopeActionAndOrderedBindings() throws Exception {
        byte[] authority = Files.readAllBytes(repositoryRoot().resolve("runtime/public-api.json"));
        try (var packaged = DepositAPI.class.getResourceAsStream("/public-api.json")) {
            assertNotNull(packaged, "Server plugin must include its procedure contract");
            assertArrayEquals(authority, packaged.readAllBytes());
        }
        var catalog = new ObjectMapper().readTree(authority);
        var names = new HashSet<String>();
        var registrations = new HashMap<String, Method>();
        for (var owner : List.of(DepositAPI.class, Discovery.class)) {
            for (Method method : owner.getDeclaredMethods()) {
                var annotation = method.getAnnotation(Procedure.class);
                if (annotation != null) {
                    assertNull(registrations.put(annotation.name(), method), annotation.name());
                }
            }
        }
        for (JsonNode entry : catalog) {
            String name = entry.path("name").asText();
            assertTrue(names.add(name), "duplicate catalog procedure " + name);
            if (!name.equals("deposit.get.info")) {
                assertCatalogEntryMatchesImplementation(entry);
            }
            assertRegistration(entry, registrations.get(name));
            assertEquals(DomainService.COMMANDS.contains(name), entry.required("batchEligible").booleanValue(), name);
        }
        assertEquals(registrations.keySet(), names);
        names.remove("deposit.get.info");
        assertEquals(ProcedureCatalog.names(), names);
    }

    private static void assertRegistration(JsonNode entry, Method method) {
        String name = entry.required("name").asText();
        assertNotNull(method, name);
        assertEquals(entry.required("mode").asText(), method.getAnnotation(Procedure.class).mode().name(), name);
        var args = entry.required("args");
        var token = entry.required("tokenIndex");
        int tokenIndex = token.isNull() ? -1 : token.intValue();
        assertTrue(token.isNull() || token.isIntegralNumber() && tokenIndex >= 0 && tokenIndex <= args.size(), name);
        assertEquals(args.size() + (token.isNull() ? 0 : 1), method.getParameterCount(), name);
        int domainIndex = 0;
        for (int i = 0; i < method.getParameterCount(); i++) {
            var parameter = method.getParameters()[i];
            var binding = parameter.getAnnotation(Name.class);
            assertNotNull(binding, name);
            if (i == tokenIndex) {
                assertEquals("token", binding.value(), name);
                assertEquals(String.class, parameter.getType(), name);
            } else {
                var arg = args.get(domainIndex++);
                assertEquals(arg.required("name").asText(), binding.value(), name + " argument " + i);
                assertEquals(arg.required("type").asText(), manifestType(parameter.getParameterizedType()), name + " argument " + i);
                assertEquals(arg.has("default") ? arg.required("default").asText() : Name.DEFAULT_VALUE,
                    binding.defaultValue(), name + " default " + binding.value());
            }
        }
        var returnType = (ParameterizedType) method.getGenericReturnType();
        var row = (Class<?>) returnType.getActualTypeArguments()[0];
        var columns = new HashMap<String, String>();
        for (var field : row.getFields()) {
            columns.put(field.getName(), manifestType(field.getGenericType()));
        }
        var declaredColumns = new HashMap<String, String>();
        entry.required("resultColumns").forEachEntry((key, value) -> declaredColumns.put(key, value.asText()));
        assertEquals(declaredColumns, columns, name + " result columns");
    }

    private static String manifestType(java.lang.reflect.Type type) {
        return type.getTypeName().replace("java.lang.", "").replace("java.util.", "")
            .replace("org.neo4j.graphdb.", "").replace(" ", "");
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

    @Test void discoveryAdvertisesTheRegisteredOperationControls() throws Exception {
        var catalog = new ObjectMapper().readTree(Files.readString(repositoryRoot().resolve("runtime/public-api.json")));
        assertTrue(((List<?>)new Discovery.Info("test").data.get("capabilities")).contains("operationControl"));
        assertEquals("WRITE",find(catalog,"deposit.action.begin").required("mode").asText());
        assertEquals("WRITE",find(catalog,"deposit.action.cancel").required("mode").asText());
        assertEquals("READ",find(catalog,"deposit.get.operation").required("mode").asText());
        var retired=Set.of("deposit.action.prepare","deposit.action.resume","deposit.action.status","deposit.action.release");
        for(var entry:catalog)assertFalse(retired.contains(entry.required("name").asText()));
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
        for (JsonNode arg : entry.path("args")) {
            if (arg.has("binding")) bindings.add(arg.path("binding").asText());
            else assertEquals("operationId", arg.required("name").asText(), name);
        }
        assertEquals(bindings, implementation.bindings(), name + " ordered bindings");
        if (implementation.action()) {
            assertEquals(bindings.size()+1, entry.required("tokenIndex").intValue(), name);
            assertEquals("operationId", entry.required("args").get(bindings.size()).required("name").asText(), name);
            assertTrue(entry.required("cancellable").booleanValue(), name);
            assertEquals(name.equals("deposit.admin.pruneResources")?"conditional":"always", entry.required("tracked").asText(), name);
        }
    }
}
