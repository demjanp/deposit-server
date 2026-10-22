package org.deposit.it;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/** One connected operational scientific workflow against the installed release listener. */
@Timeout(300)
class ScientificWorkflowIT {
    private static final String USER = "typed-data-admin";
    private static final String PASSWORD = "typed-data-account-password";
    private static final byte[] RESOURCE_BYTES = "operational specimen photograph\n".getBytes(StandardCharsets.UTF_8);
    private static Driver driver;
    private static URI downloadBase;

    private static Map<String, Object> m(Object... values) {
        return TestTransport.orderedMap(values);
    }

    private static org.neo4j.driver.Record query(String cypher, Map<String, Object> params) {
        try (var session = driver.session()) {
            return session.run("CYPHER 25 " + cypher, params,
                org.neo4j.driver.TransactionConfig.builder().withTimeout(Duration.ofSeconds(60)).build()).list().getFirst();
        }
    }

    private static org.neo4j.driver.Record call(String procedure, Object... args) {
        return TestTransport.call(driver, Duration.ofSeconds(60), procedure, args);
    }

    private static String nodeId(org.neo4j.driver.Record row) {
        return row.get("node").asNode().get("id").asString();
    }

    private static String login() {
        return call("deposit.utils.login", USER, PASSWORD).get("value").asString();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] resource(String id, long size, String token) throws IOException {
        try (var out = new ByteArrayOutputStream()) {
            for (long offset = 0; offset < size;) {
                long length = Math.min(1_048_576, size - offset);
                var row = call("deposit.resource.downloadChunk", id, offset, length, token);
                byte[] chunk = Base64.getDecoder().decode(row.get("data").get("bytesBase64").asString());
                out.write(chunk);
                offset += chunk.length;
            }
            return out.toByteArray();
        }
    }

    private static Path awaitBrowserDownload(Path directory, byte[] expected, WebDriver browser) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            try (var files = Files.list(directory)) {
                for (Path path : files.filter(Files::isRegularFile).filter(p -> !p.toString().endsWith(".part")).toList()) {
                    if (java.util.Arrays.equals(expected, Files.readAllBytes(path))) return path;
                }
            }
            Thread.sleep(100);
        }
        fail("Firefox did not download the expected bytes; URL=" + browser.getCurrentUrl() + "; body=" +
            browser.findElement(By.tagName("body")).getText());
        return null;
    }

    private static Map<String, byte[]> archive(byte[] bytes) throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.isDirectory()) entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static void assertBrowserDownload(String downloadUrl) throws Exception {
        URI browserUrl = URI.create(downloadUrl);
        assertTrue(browserUrl.toString().startsWith(downloadBase.toString() + "/resources/"));
        Path downloads = Files.createTempDirectory("operations-scientific-browser-");
        WebDriver browser = null;
        try {
            var options = new FirefoxOptions();
            var browserPath = System.getenv("SE_BROWSER_PATH");
            if (browserPath != null && !browserPath.isBlank()) options.setBinary(browserPath);
            options.addArguments("-headless");
            options.addPreference("browser.download.folderList", 2);
            options.addPreference("browser.download.dir", downloads.toAbsolutePath().toString());
            options.addPreference("browser.download.useDownloadDir", true);
            options.addPreference("browser.download.manager.showWhenStarting", false);
            options.addPreference("browser.helperApps.neverAsk.saveToDisk", "application/octet-stream,text/plain,text/csv");
            browser = new FirefoxDriver(options);
            browser.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(45));
            browser.get(browserUrl.toString());
            browser.findElement(By.name("username")).sendKeys(USER);
            browser.findElement(By.name("password")).sendKeys(PASSWORD);
            browser.findElement(By.cssSelector("form button")).click();
            var downloaded = awaitBrowserDownload(downloads, RESOURCE_BYTES, browser);
            assertTrue(downloaded.getFileName().toString().contains("specimen-photo"), "Browser download must retain the scientific resource filename");
            browser.get(downloadBase + "/login");
            new WebDriverWait(browser, Duration.ofSeconds(15)).until(ExpectedConditions.visibilityOfElementLocated(By.xpath("//*[contains(text(),'Signed in as typed-data-admin')]")));
        } finally {
            try {
                if (browser != null) browser.quit();
            } finally {
                try (var paths = Files.walk(downloads)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }

    @BeforeAll
    static void connect() throws Exception {
        downloadBase = URI.create(Objects.requireNonNull(System.getenv("DEPOSIT_TEST_HTTP_BASE"), "Installed listener URL is missing"));
        driver = GraphDatabase.driver(System.getenv("DEPOSIT_TEST_URI"), AuthTokens.basic("neo4j", System.getenv("DEPOSIT_TEST_PASSWORD")));
        driver.verifyConnectivity();
        Path secret = TestInstallationPaths.bootstrapSecret();
        if (Files.exists(secret)) call("deposit.utils.bootstrap", Files.readString(secret).trim(), USER, PASSWORD);
    }

    @AfterAll
    static void close() {
        if (driver != null) driver.close();
    }

    @Test
    void authenticatedScientificWorkflowCrossesNamespaceHistoryExchangeAndBrowserBoundaries() throws Exception {
        String sourceSession = login();
        String source = nodeId(call("deposit.create.namespace", "Operations-science-source-" + UUID.randomUUID(), m("historyMode", "persistent"), sourceSession));
        String receiverSession = login();
        String receiver = nodeId(call("deposit.create.namespace", "Operations-science-receiver-" + UUID.randomUUID(), m("historyMode", "persistent"), receiverSession));
        call("deposit.set.activeNamespace", m("id", source), sourceSession);
        call("deposit.set.activeNamespace", m("id", receiver), receiverSession);
        assertEquals(source, nodeId(call("deposit.get.activeNamespace", sourceSession)));
        assertEquals(receiver, nodeId(call("deposit.get.activeNamespace", receiverSession)));

        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var sourceWrite = workers.submit(() -> {
                start.await();
                call("deposit.create.class", "Specimen", m("descriptors", List.of(
                    m("name", "Code", "type", "String"), m("name", "Mass", "type", "Float"),
                    m("name", "Location", "type", "DGeometry"), m("name", "File", "type", "DResource"))), sourceSession);
                return true;
            });
            var receiverWrite = workers.submit(() -> {
                start.await();
                call("deposit.create.class", "ReceiverNote", m("descriptors", List.of(m("name", "Text", "type", "String"))), receiverSession);
                return true;
            });
            start.countDown();
            assertTrue(sourceWrite.get(60, TimeUnit.SECONDS));
            assertTrue(receiverWrite.get(60, TimeUnit.SECONDS));
        }
        assertEquals(1, query("MATCH (c:Class {namespaceId:$ns,name:'Specimen'}) RETURN count(c) AS n", m("ns", source)).get("n").asInt());
        assertEquals(0, query("MATCH (c:Class {namespaceId:$ns,name:'Specimen'}) RETURN count(c) AS n", m("ns", receiver)).get("n").asInt());

        String sourceToken = sourceSession;
        String receiverToken = receiverSession;
        call("deposit.create.class", "Context", m("descriptors", List.of(m("name", "Site", "type", "String"))), sourceToken);
        String contextObject = nodeId(call("deposit.create.object", "Context", m("descriptors", m("Site", "North Ridge")), sourceToken));
        String checksum = sha256(RESOURCE_BYTES);
        String upload = call("deposit.resource.beginUpload", "specimen-photo.txt", (long) RESOURCE_BYTES.length, checksum, "text/plain; charset=utf-8", sourceToken).get("data").get("uploadId").asString();
        call("deposit.resource.uploadChunk", upload, 0L, Base64.getEncoder().encodeToString(RESOURCE_BYTES), checksum, sourceToken);
        String resourceId = call("deposit.resource.finishUpload", upload, sourceToken).get("data").get("resourceId").asString();

        String specimen = nodeId(call("deposit.create.object", "Specimen", m("descriptors", m(
            "Code", "SP-11", "Mass", 42.5,
            "Location", m("wkt", "POINT Z (12 34 56)", "srid", 4326L, "verticalSrid", null, "axisOrder", "xyz", "dimensions", 3L),
            "File", m("resourceId", resourceId))), sourceToken));
        call("deposit.create.relationship", specimen, contextObject, "foundIn", m(), sourceToken);

        var originalMass = call("deposit.get.descriptor", m("objectId", specimen, "name", "Mass"), sourceToken).get("node").asNode().get("value").asDouble();
        assertEquals(42.5, originalMass);
        assertEquals("POINT Z(12 34 56)", query("MATCH (:Object {id:$id})-[:HAS_DESCRIPTOR]->(d:Descriptor {name:'Location'})-[:HAS_GEOMETRY]->(g:DGeometry) RETURN g.wkt AS wkt", m("id", specimen)).get("wkt").asString());
        var originalRevision = call("deposit.get.revision", sourceToken).get("data").get("revisionId").asString();
        call("deposit.set.descriptor", specimen, "Mass", 44.0, sourceToken);
        assertEquals(44.0, call("deposit.get.descriptor", m("objectId", specimen, "name", "Mass"), sourceToken).get("node").asNode().get("value").asDouble());
        call("deposit.utils.undo", null, sourceToken);
        assertEquals(42.5, call("deposit.get.descriptor", m("objectId", specimen, "name", "Mass"), sourceToken).get("node").asNode().get("value").asDouble());
        String undoRevision = call("deposit.get.revision", sourceToken).get("data").get("revisionId").asString();
        call("deposit.utils.redo", undoRevision, sourceToken);
        assertEquals(44.0, call("deposit.get.descriptor", m("objectId", specimen, "name", "Mass"), sourceToken).get("node").asNode().get("value").asDouble());
        assertNotEquals(originalRevision, undoRevision);

        var patternDefinition = m("objects", m(
            "specimen", m("className", "Specimen", "root", true,
                "descriptors", List.of(m("name", "Code", "type", "String")),
                "relationships", List.of(m("label", "foundIn", "targetName", "context"))),
            "context", m("className", "Context")));
        String patternId = nodeId(call("deposit.create.pattern", "OperationsSpecimenContext", patternDefinition, m(), sourceToken));
        var matches = call("deposit.get.objectsByPatternPage", m("id", patternId), m(), m(), m("pageSize", 10L), sourceToken).get("data").get("items").asList();
        assertEquals(1, matches.size());
        var bindings = (Map<?, ?>) ((Map<?, ?>) matches.getFirst()).get("bindings");
        Object selectedBinding = bindings.get("specimen");
        String selectedSpecimen = selectedBinding instanceof org.neo4j.driver.types.Node node ? node.get("id").asString() : String.valueOf(selectedBinding);
        assertEquals(specimen, selectedSpecimen);

        var exported = call("deposit.save.data", m("rootIds", List.of(selectedSpecimen)), m("maxHops", 1L, "includeHistory", false), sourceToken).get("data");
        String packageResource = exported.get("resourceId").asString();
        assertTrue(call("deposit.load.data", packageResource, sourceToken).get("data").get("valid").asBoolean());
        byte[] packageBytes = resource(packageResource, exported.get("bytes").asLong(), sourceToken);
        var archiveEntries = archive(packageBytes);
        assertFalse(archiveEntries.isEmpty(), "Pattern-selected export must contain independently readable package files");
        assertFalse(archiveEntries.containsKey("history.json"), "Subgraph exports must omit history");
        String archiveText = archiveEntries.values().stream().map(bytes -> new String(bytes, StandardCharsets.UTF_8)).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(archiveText.contains("SP-11"), "Export must include the selected specimen's typed identifier");
        assertTrue(archiveText.contains("POINT Z(12 34 56)"), "Export must include the selected geometry");
        assertTrue(archiveText.contains("specimen-photo.txt"), "Export must include the resource metadata");
        assertTrue(archiveEntries.values().stream().anyMatch(bytes -> java.util.Arrays.equals(bytes, RESOURCE_BYTES)), "Export must include the exact resource bytes");

        assertBrowserDownload(call("deposit.resource.info", resourceId, sourceToken).get("data").get("downloadUrl").asString());

        // Full persistent Namespace exports include retained history under the approved exchange contract.
        var fullExport = call("deposit.save.data", m(), m("includeHistory", true), sourceToken).get("data");
        String fullPackage = fullExport.get("resourceId").asString();
        var validated = call("deposit.load.data", fullPackage, sourceToken).get("data");
        assertTrue(validated.get("valid").asBoolean());
        assertEquals("namespace", validated.get("scope").asString());
        assertTrue(archive(resource(fullPackage, fullExport.get("bytes").asLong(), sourceToken)).containsKey("history.json"),
            "A full persistent Namespace package must include retained history");
        call("deposit.set.namespace", m("name", "Operations-science-source-renamed-" + UUID.randomUUID()), sourceToken);
        // Namespace cloning is a server-scoped mutation; refresh its independent observation first.
        call("deposit.utils.refresh", "server", sourceToken);
        String importedNamespace = call("deposit.import.namespaceData", fullPackage, sourceToken).get("data").get("namespaceId").asString();
        assertNotEquals(source, importedNamespace, "Cloning must allocate a fresh Namespace identity");
        assertNotEquals(receiver, importedNamespace, "Cloning must create a separate receiving Namespace");
        assertEquals(source, nodeId(call("deposit.get.activeNamespace", sourceToken)), "Cloning must preserve the source session's selection");
        assertEquals(receiver, nodeId(call("deposit.get.activeNamespace", receiverToken)), "Cloning must preserve the other session's selection");
        call("deposit.set.activeNamespace", m("id", importedNamespace), receiverToken);
        String importedSpecimen = query("MATCH (o:Object {namespaceId:$ns})-[:HAS_DESCRIPTOR]->(:Descriptor {name:'Code',value:'SP-11'}) RETURN o.id AS id", m("ns", importedNamespace)).get("id").asString();
        assertNotEquals(specimen, importedSpecimen, "Cloning must allocate fresh Object identities");
        assertEquals(44.0, query("MATCH (o:Object {id:$id})-[:HAS_DESCRIPTOR]->(d:Descriptor {name:'Mass'}) RETURN d.value AS value", m("id", importedSpecimen)).get("value").asDouble());
        assertEquals("POINT Z(12 34 56)", query("MATCH (:Object {id:$id})-[:HAS_DESCRIPTOR]->(d:Descriptor {name:'Location'})-[:HAS_GEOMETRY]->(g:DGeometry) RETURN g.wkt AS wkt", m("id", importedSpecimen)).get("wkt").asString());
        String importedResource = query("MATCH (:Object {id:$id})-[:HAS_DESCRIPTOR]->(:Descriptor {name:'File'})-[:HAS_RESOURCE]->(r:DResource) RETURN r.id AS id", m("id", importedSpecimen)).get("id").asString();
        assertNotEquals(resourceId, importedResource, "Cloning must allocate fresh resource identities");
        var importedInfo = call("deposit.resource.info", importedResource, receiverToken).get("data");
        assertArrayEquals(RESOURCE_BYTES, resource(importedResource, importedInfo.get("bytes").asLong(), receiverToken));
        assertEquals("SP-11", query("MATCH (o:Object {id:$id})-[:HAS_DESCRIPTOR]->(d:Descriptor {name:'Code'}) RETURN d.value AS value", m("id", importedSpecimen)).get("value").asString());
        assertEquals(44.0, call("deposit.get.descriptor", m("objectId", specimen, "name", "Mass"), sourceToken).get("node").asNode().get("value").asDouble());

        assertBrowserDownload(importedInfo.get("downloadUrl").asString());
    }
}
