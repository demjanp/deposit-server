package deposit.common;

import java.util.*;

public class GraphSONData {
	/**
	 * Parses GraphSON data into a map of GraphsonObject, GraphsonClass and GraphsonUserTool instances keyed by their node id.
	 *
	 * @param data Map<String, Object> The GraphSON data to parse
	 * @return Map<String, Map < String, Object>> {"objects": {dataId: GraphsonObject, ...}, "classes": {dataId: GraphsonClass, ...}, "userTools": {dataId: GraphsonUserTool, ...}}
	 */
	public static Map<String, Map<String, ?>> parseData(Map<String, Object> data) {
		Map<String, GraphsonObject> graphsonObjectsById = new HashMap<>();
		Map<String, GraphsonClass> graphsonClassesById = new HashMap<>();
		Map<String, GraphsonUserTool> graphsonUserToolsById = new HashMap<>();
		Map<String, List<Descriptor>> descriptors = new HashMap<>();
		Map<String, List<ClassDescriptor>> classDescriptors = new HashMap<>();

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
				GraphsonObject graphsonObject = new GraphsonObject();
				graphsonObject.id = dataId;
				graphsonObject.className = "";
				graphsonObject.originalId = properties.getOrDefault("id", "0").toString();
				graphsonObject.position = (properties.get("position") instanceof List) ? (List<Double>) properties.get("position") : List.of();
				graphsonObject.descriptors = new ArrayList<>();
				graphsonObject.relationships = new ArrayList<>();
				graphsonObjectsById.put(dataId, graphsonObject);
			
			} else if (label.equals("Class")) {
				GraphsonClass graphsonClass = new GraphsonClass();
				graphsonClass.id = dataId;
				graphsonClass.name = properties.get("name").toString();
				graphsonClass.order = (Long) properties.getOrDefault("order", 0L);
				graphsonClass.position = (properties.get("position") instanceof List) ? (List<Double>) properties.get("position") : List.of();;
				graphsonClass.comment = properties.getOrDefault("comment", "").toString();
				graphsonClass.descriptors = new ArrayList<>();
				graphsonClass.relationships = new ArrayList<>();
				graphsonClassesById.put(dataId, graphsonClass);
			
			} else if (label.equals("Descriptor")) {
				
				// Object Descriptor
				if (properties.containsKey("value")) {
					Descriptor descriptor = new Descriptor();
					descriptor.name = properties.get("name").toString();
					descriptor.value = properties.get("value").toString();
					descriptors.computeIfAbsent(dataId, k -> new ArrayList<>()).add(descriptor);
				
				// Class Descriptor
				} else {
					ClassDescriptor classDescriptor = new ClassDescriptor();
					classDescriptor.name = properties.get("name").toString();
					classDescriptor.type = properties.get("type").toString();
					classDescriptor.order = (Long) properties.getOrDefault("order", 0L);
					classDescriptor.comment = properties.getOrDefault("comment", "").toString();
					classDescriptors.computeIfAbsent(dataId, k -> new ArrayList<>()).add(classDescriptor);
				}
			
			} else if (label.equals("UserTool")) {
				GraphsonUserTool graphsonUserTool = new GraphsonUserTool();
				graphsonUserTool.id = dataId;
				graphsonUserTool.name = properties.get("name").toString();
				graphsonUserTool.data = properties.get("data").toString();
				graphsonUserTool.comment = properties.getOrDefault("comment", "").toString();
				graphsonUserToolsById.put(dataId, graphsonUserTool);
			}
		}
		
		// Parse edges
		for (Map<String, Object> edge : edges) {
			String startId = edge.get("outV").toString();
			String endId = edge.get("inV").toString();
			String type = edge.get("type").toString();
			Map<String, Object> edgeProperties = (Map<String, Object>) edge.get("properties");
			
			if (type.equals("MEMBER_OF") && graphsonObjectsById.containsKey(startId) && graphsonClassesById.containsKey(endId)) {
				graphsonObjectsById.get(startId).className = graphsonClassesById.get(endId).name;
			} else if (type.equals("HAS_DESCRIPTOR")) {
				
				// Object Descriptor
				if (graphsonObjectsById.containsKey(startId) && descriptors.containsKey(endId)) {
					graphsonObjectsById.get(startId).descriptors.addAll(descriptors.get(endId));
				
				// Class Descriptor
				} else if (graphsonClassesById.containsKey(startId) && classDescriptors.containsKey(endId)) {
					graphsonClassesById.get(startId).descriptors.addAll(classDescriptors.get(endId));
				}
			
			} else if (type.equals("RELATED_TO")) {
				
				// Object Relationship
				if (graphsonObjectsById.containsKey(startId) && graphsonObjectsById.containsKey(endId)) {
					Relationship relationship = new Relationship();
					relationship.label = edgeProperties.get("label").toString();
					relationship.targetId = endId;
					relationship.weight = (edgeProperties.get("weight") instanceof Double) ? (Double) edgeProperties.get("weight") : 0.0;
					graphsonObjectsById.get(startId).relationships.add(relationship);
				
				// Class Relationship
				} else if (graphsonClassesById.containsKey(startId) && graphsonClassesById.containsKey(endId)) {
					ClassRelationship relationship = new ClassRelationship();
					relationship.label = edgeProperties.get("label").toString();
					relationship.targetId = endId;
					relationship.comment = edgeProperties.getOrDefault("comment", "").toString();
					graphsonClassesById.get(startId).relationships.add(relationship);
				}
			}
		}
		
		return Map.of(
			"objects", graphsonObjectsById,
			"classes", graphsonClassesById,
			"userTools", graphsonUserToolsById
		);
	}
	
	/**
	 * Find weakly connected components in the graph, returning lists of GraphsonObject
	 *
	 * @param graphsonObjects Map<String, GraphsonObject> graph represented by a Map of {dataId: GraphsonObject, ...}
	 * @return List<List<GraphsonObject>> list of weakly connected components (disconnected graph subtrees)
	 */
	public static List<List<GraphsonObject>> findSubgraphs(Map<String, GraphsonObject> graphsonObjects) {
		// Step 1: Build the adjacency list and map of GraphsonObject by id
		Map<String, Set<String>> adjacencyList = new HashMap<>();

		// Populate the adjacency list
		for (GraphsonObject obj : graphsonObjects.values()) {
			adjacencyList.putIfAbsent(obj.id, new HashSet<>());
			for (Relationship relation : obj.relationships) {
				adjacencyList.putIfAbsent(relation.targetId, new HashSet<>());
				// Add both directions (ignoring the direction of edges for weakly connected components)
				adjacencyList.get(obj.id).add(relation.targetId);
				adjacencyList.get(relation.targetId).add(obj.id);
			}
		}

		// Step 2: Find weakly connected components using BFS/DFS
		List<List<GraphsonObject>> components = new ArrayList<>();
		Set<String> visited = new HashSet<>();
		for (String nodeId : adjacencyList.keySet()) {
			if (!visited.contains(nodeId)) {
				// New component found
				List<GraphsonObject> component = new ArrayList<>();
				bfs(nodeId, adjacencyList, graphsonObjects, visited, component);
				components.add(component);
			}
		}
		return components;
	}
	
	public static class GraphsonObject {
		public String id;
		public String className;
		public String originalId;
		public List<Double> position;
		public List<Descriptor> descriptors;
		public List<Relationship> relationships;
		
		@Override
		public String toString() {
			StringBuilder toReturn = new StringBuilder();
			toReturn.append("Object ").append(id).append("(").append(className).append(")");
			List<String> descriptorStrings = new ArrayList<>();
			for (Descriptor descriptor : descriptors) {
				descriptorStrings.add(descriptor.toString());
			}
			if (!descriptorStrings.isEmpty()) {
				toReturn.append(" {" + String.join(", ", descriptorStrings) + "}");
			}
			toReturn.append("\norigId: ").append(originalId);
			for (Relationship relationship : relationships) {
				toReturn.append("\n\t").append(relationship.toString());
			}
			toReturn.append("\n");
			return toReturn.toString();
		}
	}
	
	public static class Descriptor {
		public String name;
		public String value;

		@Override
		public String toString() {
			return name + ": " + value;
		}
	}
	
	public static class Relationship {
		public String label;
		public String targetId;
		public Double weight;
		
		@Override
		public String toString() {
			return "-[" + label + "]->(" + targetId + ")";
		}
	}
	
	public static class GraphsonClass {
		public String id;
		public String name;
		public Long order;
		public List<Double> position;
		public String comment;
		public List<ClassDescriptor> descriptors;
		public List<ClassRelationship> relationships;
		
		@Override
		public String toString() {
			StringBuilder toReturn = new StringBuilder();
			toReturn.append("Class ").append(id).append(" : ").append(name);
			List<String> descriptorStrings = new ArrayList<>();
			for (ClassDescriptor descriptor : descriptors) {
				descriptorStrings.add(descriptor.toString());
			}
			if (!descriptorStrings.isEmpty()) {
				toReturn.append(" {" + String.join(", ", descriptorStrings) + "}");
			}
			for (ClassRelationship relationship : relationships) {
				toReturn.append("\n\t").append(relationship.toString());
			}
			toReturn.append("\n");
			return toReturn.toString();
		}
	}
	
	public static class ClassDescriptor {
		public String name;
		public String type;
		public Long order;
		public String comment;

		@Override
		public String toString() {
			return name + " (" + type + ")";
		}
	}
	
	public static class ClassRelationship {
		public String label;
		public String targetId;
		public String comment;
		
		@Override
		public String toString() {
			return "-[" + label + "]->(" + targetId + ")";
		}
	}
	
	public static class GraphsonUserTool {
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
	private static void bfs(String startNode, Map<String, Set<String>> adjacencyList, Map<String, GraphsonObject> objectById, Set<String> visited, List<GraphsonObject> component) {
		Queue<String> queue = new LinkedList<>();
		queue.add(startNode);
		visited.add(startNode);
		while (!queue.isEmpty()) {
			String currentNode = queue.poll();
			component.add(objectById.get(currentNode));
			for (String neighbor : adjacencyList.get(currentNode)) {
				if (!visited.contains(neighbor)) {
					visited.add(neighbor);
					queue.add(neighbor);
				}
			}
		}
	}
}