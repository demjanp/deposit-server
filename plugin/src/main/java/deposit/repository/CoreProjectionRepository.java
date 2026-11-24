package deposit.repository;

import deposit.common.Json;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import org.neo4j.graphdb.*;

/** Portable projections for live graph entities. History images have a separate contract. */
final class CoreProjectionRepository {
    interface Access {
        Node objectClass(Node object);
        List<Node> children(Node owner, String label);
        List<Node> nodes(String label, Map<String,Object> properties);
        Object descriptorValue(Node descriptor);
        String descriptorType(Node descriptor);
        Node geometry(Node descriptor);
    }

    private final Access access;

    CoreProjectionRepository(Access access) { this.access = access; }

    TreeMap<String,Map<String,String>> namespaceSchema(String namespace) {
        var schema = new TreeMap<String,Map<String,String>>();
        for (Node clazz : access.nodes("Class", Map.of("namespaceId", namespace))) {
            var declarations = new TreeMap<String,String>();
            for (Node declaration : access.children(clazz, "ClassDescriptor")) {
                declarations.put((String)declaration.getProperty("name"), (String)declaration.getProperty("type"));
            }
            schema.put((String)clazz.getProperty("name"), declarations);
        }
        return schema;
    }

    Map<String,Object> data(Node node) { return data(node, true); }

    Map<String,Object> data(Node node, boolean includeOwnedValues) {
        String kind = CoreRepository.kind(node);
        var result = new LinkedHashMap<String,Object>();
        if (!kind.equals("User")) result.put("kind", kind);
        for (String key : List.of("id","namespaceId","createdAt","createdBy","updatedAt","updatedBy","name","username","enabled","serverRole","description","historyMode","historyEpochId","sequence","order","comment","type")) {
            if (node.hasProperty(key)) {
                Object value = node.getProperty(key);
                result.put(key, value instanceof TemporalAccessor ? value.toString() : value);
            }
        }
        if (!kind.equals("User") && !result.containsKey("namespaceId")) result.put("namespaceId", kind.equals("Namespace") ? CoreRepository.id(node) : null);
        if (node.hasProperty("position")) result.put("position", Arrays.stream((double[])node.getProperty("position")).boxed().toList());
        if (kind.equals("Object")) {
            result.put("classId", CoreRepository.id(access.objectClass(node)));
            var descriptors = new LinkedHashMap<String,Object>();
            if (includeOwnedValues) for (Node descriptor : access.children(node, "Descriptor")) descriptors.put((String)descriptor.getProperty("name"), access.descriptorValue(descriptor));
            result.put("descriptors", descriptors);
        }
        if (kind.equals("Class") && includeOwnedValues) result.put("descriptors", access.children(node, "ClassDescriptor").stream().map(this::data).toList());
        if (kind.equals("Descriptor")) {
            result.put("objectId", node.getProperty("ownerId"));
            result.put("value", access.descriptorValue(node));
        }
        if (kind.equals("Descriptor") && access.descriptorType(node).equals("DGeometry")) {
            Node geometry = access.geometry(node);
            result.put("geometryId", geometry == null ? null : CoreRepository.id(geometry));
        }
        if (kind.equals("ClassDescriptor")) result.put("classId", node.getProperty("ownerId"));
        if (kind.equals("Pattern")) result.put("definition", Json.read((String)node.getProperty("definition")));
        if (kind.equals("UserTool")) result.put("data", node.getProperty("data"));
        if (kind.equals("DGeometry")) {
            for (String key : List.of("geometryType","wkt","srid","verticalSrid","axisOrder","dimensions")) if (node.hasProperty(key)) result.put(key, node.getProperty(key));
            var edge = node.getSingleRelationship(RelationshipType.withName("HAS_GEOMETRY"), Direction.INCOMING);
            if (edge != null) result.put("descriptorId", CoreRepository.id(edge.getStartNode()));
        }
        if (kind.equals("DResource")) for (String key : List.of("filename","bytes","mediaType","checksum","state")) if (node.hasProperty(key)) result.put(key, node.getProperty(key));
        return result;
    }

    Map<String,Object> data(Relationship relationship) {
        String kind = CoreRepository.kind(relationship);
        var result = new LinkedHashMap<String,Object>();
        result.put("kind", kind);
        relationship.getAllProperties().forEach((key, value) -> {
            if (!CoreRepository.IDENTITY_KEY.equals(key)) result.put(key, value instanceof TemporalAccessor ? value.toString() : value);
        });
        if (kind.equals("Membership")) {
            result.put("objectId", CoreRepository.id(relationship.getStartNode()));
            result.put("classId", CoreRepository.id(relationship.getEndNode()));
        } else {
            result.put("sourceId", CoreRepository.id(relationship.getStartNode()));
            result.put("targetId", CoreRepository.id(relationship.getEndNode()));
            if (kind.equals("Relationship")) result.putIfAbsent("weight", null);
        }
        return result;
    }
}
