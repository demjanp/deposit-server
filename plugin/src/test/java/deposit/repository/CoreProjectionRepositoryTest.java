package deposit.repository;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.FileSystems;
import java.time.ZonedDateTime;
import java.util.*;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.graphdb.*;
import org.junit.jupiter.api.Test;
import deposit.common.ExceptionHandler;
import deposit.common.PatternCodec;

class CoreProjectionRepositoryTest {
    @TempDir Path temporaryDirectory;

    private Path protectedDirectory() throws Exception {
        Path path = Files.createDirectory(temporaryDirectory.resolve("secrets"));
        UserPrincipal runtime = FileSystems.getDefault().getUserPrincipalLookupService()
            .lookupPrincipalByName(ProcessHandle.current().info().user().orElseThrow());
        if (!Files.getOwner(path).equals(runtime)) Files.setOwner(path, runtime);
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(runtime)
            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
            .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build()));
        return path;
    }

    private static Node node(Map<String,Object> properties) {
        return (Node)Proxy.newProxyInstance(Node.class.getClassLoader(), new Class<?>[]{Node.class}, (proxy, method, args) -> {
            if (method.getName().equals("getProperty")) {
                Object value = properties.get(args[0]);
                if (value == null && args.length == 2) return args[1];
                if (value == null) throw new IllegalArgumentException("Missing property: " + args[0]);
                return value;
            }
            if (method.getName().equals("toString")) return properties.toString();
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            throw new UnsupportedOperationException(method.toString());
        });
    }

    @Test void namespaceSchemaIsSortedFreshMutableAndReflectsCurrentAccessState() {
        var classes = new ArrayList<Node>();
        var declarations = new IdentityHashMap<Node,List<Node>>();
        Node beta = node(Map.of("namespaceId", "ns", "name", "Beta"));
        Node alpha = node(Map.of("namespaceId", "ns", "name", "Alpha"));
        classes.add(beta);
        classes.add(alpha);
        declarations.put(alpha, new ArrayList<>(List.of(
            node(Map.of("name", "Zeta", "type", "String")),
            node(Map.of("name", "AlphaField", "type", "Integer")))));
        declarations.put(beta, new ArrayList<>(List.of(node(Map.of("name", "Only", "type", "Boolean")))));
        var access = new CoreProjectionRepository.Access() {
            @Override public List<Node> nodes(String label, Map<String,Object> properties) {
                assertEquals("Class", label);
                return classes.stream().filter(n -> properties.get("namespaceId").equals(n.getProperty("namespaceId"))).toList();
            }
            @Override public List<Node> children(Node owner, String label) {
                assertEquals("ClassDescriptor", label);
                return declarations.get(owner);
            }
            @Override public Node objectClass(Node object) { throw new UnsupportedOperationException(); }
            @Override public Object descriptorValue(Node descriptor) { throw new UnsupportedOperationException(); }
            @Override public String descriptorType(Node descriptor) { throw new UnsupportedOperationException(); }
            @Override public Node geometry(Node descriptor) { throw new UnsupportedOperationException(); }
        };
        var projections = new CoreProjectionRepository(access);

        var first = projections.namespaceSchema("ns");
        assertEquals(List.of("Alpha", "Beta"), new ArrayList<>(first.keySet()));
        assertEquals(List.of("AlphaField", "Zeta"), new ArrayList<>(first.get("Alpha").keySet()));
        first.get("Alpha").put("CallerOverlay", "Float");
        first.put("CallerClass", new TreeMap<>());

        declarations.get(alpha).add(node(Map.of("name", "NewField", "type", "Long")));
        var second = projections.namespaceSchema("ns");
        assertNotSame(first, second);
        assertNotSame(first.get("Alpha"), second.get("Alpha"));
        assertEquals(List.of("Alpha", "Beta"), new ArrayList<>(second.keySet()));
        assertEquals(List.of("AlphaField", "NewField", "Zeta"), new ArrayList<>(second.get("Alpha").keySet()));
        assertFalse(second.containsKey("CallerClass"));
        assertFalse(second.get("Alpha").containsKey("CallerOverlay"));
        second.get("Alpha").put("Mutable", "String");
        assertEquals("String", second.get("Alpha").get("Mutable"));
    }

    @Test void namespaceSchemaReadsUncommittedChangesFromItsSuppliedTransaction() throws Exception {
        DatabaseManagementService management = null;
        String previousConfig = System.getProperty("deposit.config");
        try {
            Path configDirectory = Files.createDirectories(temporaryDirectory.resolve("install/config"));
            Path config = configDirectory.resolve("deposit.properties");
            Files.writeString(config, "secretRoot=" + protectedDirectory().toString().replace('\\', '/') + "\n");
            System.setProperty("deposit.config", config.toString());
            management = new DatabaseManagementServiceBuilder(temporaryDirectory.resolve("neo4j")).build();
            GraphDatabaseService database = management.database("neo4j");
            try (Transaction tx = database.beginTx()) {
                Node clazz = tx.createNode(Label.label("Class"));
                clazz.setProperty("id", UUID.randomUUID().toString());
                clazz.setProperty("namespaceId", "tx-namespace");
                clazz.setProperty("name", "CurrentClass");
                Node declaration = tx.createNode(Label.label("ClassDescriptor"));
                declaration.setProperty("id", UUID.randomUUID().toString());
                declaration.setProperty("namespaceId", "tx-namespace");
                declaration.setProperty("ownerId", clazz.getProperty("id"));
                declaration.setProperty("name", "CurrentField");
                declaration.setProperty("type", "String");
                clazz.createRelationshipTo(declaration, RelationshipType.withName("HAS_DESCRIPTOR"));

                var schema = new CoreRepository(tx, ZonedDateTime.now()).namespaceSchema("tx-namespace");
                assertEquals(Map.of("CurrentField", "String"), schema.get("CurrentClass"));
                var pattern = Map.<String,Object>of("objects", Map.of("root", Map.of(
                    "className", "CurrentClass", "root", true,
                    "descriptors", List.of(Map.of("name", "CurrentField", "type", "String")))));
                assertDoesNotThrow(() -> PatternCodec.validate(List.of(pattern), schema),
                    "Pattern compatibility must see schema writes that are still uncommitted in this transaction");
                var incompatible = Map.<String,Object>of("objects", Map.of("root", Map.of(
                    "className", "CurrentClass", "root", true,
                    "descriptors", List.of(Map.of("name", "CurrentField", "type", "Integer")))));
                assertThrows(ExceptionHandler.DepositException.class, () -> PatternCodec.validate(List.of(incompatible), schema),
                    "Current transaction declarations must participate in Pattern type-conflict validation");
            }
        } finally {
            if (management != null) management.shutdown();
            if (previousConfig == null) System.clearProperty("deposit.config");
            else System.setProperty("deposit.config", previousConfig);
        }
    }
}
