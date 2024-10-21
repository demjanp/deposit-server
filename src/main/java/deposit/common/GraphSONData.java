package deposit.common;

import java.util.*;

public class GraphSONData {
	/**
	 * Parses GraphSON data into a map of GSObject, GSClass and GSUserTool instances keyed by their node id.
	 *
	 * @param data Map<String, Object> The GraphSON data to parse
	 * @return Map<String, Map < String, Object>> {"objects": {dataId: GSObject, ...}, "classes": {dataId: GSClass, ...}, "userTools": {dataId: GSUserTool, ...}}
	 */
	public static Map<String, Map<String, GSElement>> parseData(Map<String, Object> data) {
		Map<String, GSElement> gsObjectsById = new HashMap<>();
		Map<String, GSElement> gsClassesById = new HashMap<>();
		Map<String, GSElement> gsUserToolsById = new HashMap<>();
		Map<String, GSDescriptor> gsDescriptorsById = new HashMap<>();
		Map<String, GSDResource> gsDResourcesById = new HashMap<>();
		Map<String, GSClassDescriptor> gsClassDescriptorsById = new HashMap<>();

		if (data.containsKey("graph")) {
			data = (Map<String, Object>) data.get("graph");
		}

		List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
		List<Map<String, Object>> edges = (List<Map<String, Object>>) data.get("edges");
		
		if (nodes == null || edges == null) {
			ExceptionHandler.throwException("Data must contain 'nodes' and 'edges'.");
		}
		
		// Parse nodes
		for (Map<String, Object> node : nodes) {
			String label = node.get("label").toString();
			String dataId = node.get("id").toString();
			Map<String, Object> properties = (Map<String, Object>) node.getOrDefault("properties", new HashMap<>());
			
			if (label.equals("Object")) {
				GSObject gsObject = new GSObject();
				gsObject.id = dataId;
				gsObject.className = "";
				gsObject.originalId = properties.getOrDefault("id", "0").toString();
				gsObject.position = (properties.get("position") instanceof List) ? (List<Double>) properties.get("position") : List.of();
				gsObject.descriptors = new ArrayList<>();
				gsObject.relationships = new ArrayList<>();
				gsObjectsById.put(dataId, gsObject);
			
			} else if (label.equals("Class")) {
				GSClass gsClass = new GSClass();
				gsClass.id = dataId;
				gsClass.name = properties.get("name").toString();
				gsClass.order = (Long) properties.getOrDefault("order", 0L);
				gsClass.position = (properties.get("position") instanceof List) ? (List<Double>) properties.get("position") : List.of();;
				gsClass.comment = properties.getOrDefault("comment", "").toString();
				gsClass.descriptors = new ArrayList<>();
				gsClass.relationships = new ArrayList<>();
				gsClassesById.put(dataId, gsClass);
			
			} else if (label.equals("Descriptor")) {
				
				// Object Descriptor
				if (properties.containsKey("value")) {
					GSDescriptor descriptor = new GSDescriptor();
					descriptor.name = properties.get("name").toString();
					descriptor.value = properties.get("value");
					descriptor.resource = null;
					gsDescriptorsById.put(dataId, descriptor);
				
				// Class Descriptor
				} else {
					GSClassDescriptor descriptor = new GSClassDescriptor();
					descriptor.name = properties.get("name").toString();
					descriptor.type = properties.get("type").toString();
					descriptor.order = (Long) properties.getOrDefault("order", 0L);
					descriptor.comment = properties.getOrDefault("comment", "").toString();
					gsClassDescriptorsById.put(dataId, descriptor);
				}
			
			} else if (label.equals("DResource")) {
					GSDResource resource = new GSDResource();
					resource.filename = properties.get("filename").toString();
					resource.storedFilename = properties.get("storedFilename").toString();
					resource.imageFormat = properties.get("imageFormat").toString();
					gsDResourcesById.put(dataId, resource);

			} else if (label.equals("UserTool")) {
				GSUserTool userTool = new GSUserTool();
				userTool.id = dataId;
				userTool.name = properties.get("name").toString();
				userTool.data = properties.get("data").toString();
				userTool.comment = properties.getOrDefault("comment", "").toString();
				gsUserToolsById.put(dataId, userTool);
			}
		}
		
		// Parse edges
		for (Map<String, Object> edge : edges) {
			String startId = edge.get("outV").toString();
			String endId = edge.get("inV").toString();
			String type = edge.get("type").toString();
			Map<String, Object> edgeProperties = (Map<String, Object>) edge.get("properties");
			
			if (type.equals("MEMBER_OF") && gsObjectsById.containsKey(startId) && gsClassesById.containsKey(endId)) {
				((GSObject) gsObjectsById.get(startId)).className = ((GSClass) gsClassesById.get(endId)).name;
			} else if (type.equals("HAS_DESCRIPTOR")) {
				
				// Object Descriptor
				if (gsObjectsById.containsKey(startId) && gsDescriptorsById.containsKey(endId)) {
					((GSObject) gsObjectsById.get(startId)).descriptors.add(gsDescriptorsById.get(endId));
				
				// Class Descriptor
				} else if (gsClassesById.containsKey(startId) && gsClassDescriptorsById.containsKey(endId)) {
					((GSClass) gsClassesById.get(startId)).descriptors.add(gsClassDescriptorsById.get(endId));
				}
			
			} else if (type.equals("HAS_RESOURCE") && gsDescriptorsById.containsKey(startId) && gsDResourcesById.containsKey(endId)) {
				gsDescriptorsById.get(startId).resource = gsDResourcesById.get(endId);

			} else if (type.equals("RELATED_TO")) {

				// Object Relationship
				if (gsObjectsById.containsKey(startId) && gsObjectsById.containsKey(endId)) {
					GSRelationship relationship = new GSRelationship();
					relationship.label = edgeProperties.get("label").toString();
					relationship.targetId = endId;
					relationship.weight = (edgeProperties.get("weight") instanceof Double) ? (Double) edgeProperties.get("weight") : 0.0;
					((GSObject) gsObjectsById.get(startId)).relationships.add(relationship);
				
				// Class Relationship
				} else if (gsClassesById.containsKey(startId) && gsClassesById.containsKey(endId)) {
					GSClassRelationship relationship = new GSClassRelationship();
					relationship.label = edgeProperties.get("label").toString();
					relationship.targetId = endId;
					relationship.comment = edgeProperties.getOrDefault("comment", "").toString();
					((GSClass) gsClassesById.get(startId)).relationships.add(relationship);
				}
			}
		}
		
		return Map.of(
				"objects", gsObjectsById,
				"classes", gsClassesById,
				"userTools", gsUserToolsById
		);
	}
	
	/**
	 * Find weakly connected components in the graph, returning lists of GSObject
	 *
	 * @param graphsonObjects Map<String, GSObject> graph represented by a Map of {dataId: GSObject, ...}
	 * @return List<List<GSObject>> list of weakly connected components (disconnected graph subtrees)
	 */
	public static List<List<GSElement>> findSubgraphs(Map<String, GSElement> graphsonObjects) {
		// Step 1: Build the adjacency list and map of GSObject by id
		Map<String, Set<String>> adjacencyList = new HashMap<>();

		// Populate the adjacency list
		for (GSElement element : graphsonObjects.values()) {
			GSObject obj = (GSObject) element;
			adjacencyList.putIfAbsent(obj.id, new HashSet<>());
			for (GSRelationship relationship : obj.relationships) {
				adjacencyList.putIfAbsent(relationship.targetId, new HashSet<>());
				// Add both directions (ignoring the direction of edges for weakly connected components)
				adjacencyList.get(obj.id).add(relationship.targetId);
				adjacencyList.get(relationship.targetId).add(obj.id);
			}
		}

		// Step 2: Find weakly connected components using BFS/DFS
		List<List<GSElement>> components = new ArrayList<>();
		Set<String> visited = new HashSet<>();
		for (String nodeId : adjacencyList.keySet()) {
			if (!visited.contains(nodeId)) {
				// New component found
				List<GSElement> component = new ArrayList<>();
				bfs(nodeId, adjacencyList, graphsonObjects, visited, component);
				components.add(component);
			}
		}
		return components;
	}

	public static class GSElement{
		public String toValues(Map<String, GSElement> gsMap){
			return toString();
		}

		public String toValuesIndented(Map<String, GSElement> gsMap, int indent) {
			return toString();
		}
	}

	public static class GSObject extends GSElement {
		public String id;
		public String className;
		public String originalId;
		public List<Double> position;
		public List<GSDescriptor> descriptors;
		public List<GSRelationship> relationships;

		@Override
		public String toValues(Map<String, GSElement> graphsonObjects) {
			return toValuesIndented(graphsonObjects, 0);
		}

		public String toValuesIndented(Map<String, GSElement> graphsonObjects, int indent) {
			StringBuilder toReturn = new StringBuilder();
			toReturn.append("Object (").append(className).append(")");
			List<String> descriptorStrings = new ArrayList<>();
			for (GSDescriptor descriptor : descriptors) {
				descriptorStrings.add(descriptor.toString());
			}
			if (!descriptorStrings.isEmpty()) {
				toReturn.append(" {" + String.join(", ", descriptorStrings.stream().sorted().toList()) + "}");
			}
			List<String> relationshipStrings = new ArrayList<>();
			for (GSRelationship relationship : relationships) {
				relationshipStrings.add("\n\t" + relationship.toValuesIndented(graphsonObjects, indent));
			}
			if (!relationshipStrings.isEmpty()) {
				toReturn.append(String.join("", relationshipStrings.stream().sorted().toList()));
			}
			return toReturn.toString();
		}
		
		@Override
		public String toString() {
			StringBuilder toReturn = new StringBuilder();
			toReturn.append("Object ").append(id).append("(").append(className).append(")");
			List<String> descriptorStrings = new ArrayList<>();
			for (GSDescriptor descriptor : descriptors) {
				descriptorStrings.add(descriptor.toString());
			}
			if (!descriptorStrings.isEmpty()) {
				toReturn.append(" {" + String.join(", ", descriptorStrings) + "}");
			}
			toReturn.append("\norigId: ").append(originalId);
			for (GSRelationship relationship : relationships) {
				toReturn.append("\n\t").append(relationship.toString());
			}
			return toReturn.toString();
		}
	}
	
	public static class GSDescriptor {
		public String name;
		public Object value;
		public GSDResource resource;

		@Override
		public String toString() {
			return name + ": " + value;
		}
	}

	public static class GSDResource {
		public String filename;
		public String storedFilename;
		public String imageFormat;

		@Override
		public String toString() {
			return "GSDResource(" + filename + ")";
		}
	}
	
	public static class GSRelationship extends GSElement {
		public String label;
		public String targetId;
		public Double weight;

		@Override
		public String toValues(Map<String, GSElement> graphsonObjects) {
			return toValuesIndented(graphsonObjects, 0);
		}

		public String toValuesIndented(Map<String, GSElement> graphsonObjects, int indent) {
			return "\t".repeat(indent + 1) + "-[" + label + "]->(" + graphsonObjects.get(targetId).toValuesIndented(graphsonObjects, indent + 1) + ")";
		}
		
		@Override
		public String toString() {
			return "-[" + label + "]->(" + targetId + ")";
		}
	}
	
	public static class GSClass extends GSElement {
		public String id;
		public String name;
		public Long order;
		public List<Double> position;
		public String comment;
		public List<GSClassDescriptor> descriptors;
		public List<GSClassRelationship> relationships;

		@Override
		public String toValues(Map<String, GSElement> graphsonClasses) {
			return toValuesIndented(graphsonClasses, 0);
		}

		public String toValuesIndented(Map<String, GSElement> graphsonClasses, int indent) {
			StringBuilder toReturn = new StringBuilder();
			toReturn.append("Class : ").append(name);
			List<String> descriptorStrings = new ArrayList<>();
			for (GSClassDescriptor descriptor : descriptors) {
				descriptorStrings.add(descriptor.toString());
			}
			if (!descriptorStrings.isEmpty()) {
				toReturn.append(" {" + String.join(", ", descriptorStrings.stream().sorted().toList()) + "}");
			}
			List<String> relationshipStrings = new ArrayList<>();
			for (GSClassRelationship relationship : relationships) {
				relationshipStrings.add("\n\t" + relationship.toValuesIndented(graphsonClasses, indent));
			}
			if (!relationshipStrings.isEmpty()) {
				toReturn.append(String.join("", relationshipStrings.stream().sorted().toList()));
			}
			return toReturn.toString();
		}

		@Override
		public String toString() {
			StringBuilder toReturn = new StringBuilder();
			toReturn.append("Class ").append(id).append(" : ").append(name);
			List<String> descriptorStrings = new ArrayList<>();
			for (GSClassDescriptor descriptor : descriptors) {
				descriptorStrings.add(descriptor.toString());
			}
			if (!descriptorStrings.isEmpty()) {
				toReturn.append(" {" + String.join(", ", descriptorStrings) + "}");
			}
			for (GSClassRelationship relationship : relationships) {
				toReturn.append("\n\t").append(relationship.toString());
			}
			toReturn.append("\n");
			return toReturn.toString();
		}
	}
	
	public static class GSClassDescriptor {
		public String name;
		public String type;
		public Long order;
		public String comment;

		@Override
		public String toString() {
			return name + " (" + type + ")";
		}
	}
	
	public static class GSClassRelationship extends GSElement {
		public String label;
		public String targetId;
		public String comment;

		@Override
		public String toValues(Map<String, GSElement> graphsonClasses) {
			return toValuesIndented(graphsonClasses, 0);
		}

		public String toValuesIndented(Map<String, GSElement> graphsonClasses, int indent) {
			return "\t".repeat(indent + 1) + "-[" + label + "]->(" + graphsonClasses.get(targetId).toValuesIndented(graphsonClasses, indent + 1) + ")";
		}

		@Override
		public String toString() {
			return "-[" + label + "]->(" + targetId + ")";
		}
	}
	
	public static class GSUserTool extends GSElement {
		public String id;
		public String name;
		public String data;
		public String comment;
		
		@Override
		public String toString() {
			return "UserTool " + id + " : " + name;
		}
	}
	
	// BFS method to traverse the graph and find all GraphsonObjects in the same component
	private static void bfs(String startNode, Map<String, Set<String>> adjacencyList, Map<String, GSElement> objectById, Set<String> visited, List<GSElement> component) {
		Queue<String> queue = new LinkedList<>();
		queue.add(startNode);
		visited.add(startNode);
		while (!queue.isEmpty()) {
			String currentNode = queue.poll();
			component.add((GSObject) objectById.get(currentNode));
			for (String neighbor : adjacencyList.get(currentNode)) {
				if (!visited.contains(neighbor)) {
					visited.add(neighbor);
					queue.add(neighbor);
				}
			}
		}
	}
}