package deposit.repository;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.neo4j.graphdb.Node;

class CoreProjectionRepositoryTest {
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
}
