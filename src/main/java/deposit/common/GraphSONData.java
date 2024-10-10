package deposit.common;

import java.util.*;

public class GraphSONData {
	/**
	 * Parses GraphSON data into a map of GraphsonObject instances keyed by their node id.
	 *
	 * @param data Map<String, Object> The GraphSON data to parse
	 * @return Map<String, GraphsonObject> {dataId: GraphsonObject, ...}
	 */
	public static Map<String, GraphsonObject> parseData(Map<String, Object> data) {
		Map<String, GraphsonObject> graphsonObjectsById = new HashMap<>();
		Map<String, String> classes = new HashMap<>();
		Map<String, List<Descriptor>> descriptors = new HashMap<>();
		
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
				graphsonObject.descriptors = new ArrayList<>();
				graphsonObject.relatedTo = new ArrayList<>();
				graphsonObjectsById.put(dataId, graphsonObject);
			} else if (label.equals("Class")) {
				classes.put(dataId, properties.get("name").toString());
			} else if (label.equals("Descriptor")) {
				if (properties.containsKey("value")) {
					Descriptor descriptor = new Descriptor();
					descriptor.name = properties.get("name").toString();
					descriptor.value = properties.get("value").toString();
					descriptors.computeIfAbsent(dataId, k -> new ArrayList<>()).add(descriptor);
				}
			}
		}
		
		// Parse edges
		for (Map<String, Object> edge : edges) {
			String startId = edge.get("outV").toString();
			String endId = edge.get("inV").toString();
			String type = edge.get("type").toString();
			Map<String, Object> edgeProperties = (Map<String, Object>) edge.get("properties");
			
			if (type.equals("MEMBER_OF") && graphsonObjectsById.containsKey(startId) && classes.containsKey(endId)) {
				graphsonObjectsById.get(startId).className = classes.get(endId);
			} else if (type.equals("HAS_DESCRIPTOR") && graphsonObjectsById.containsKey(startId) && descriptors.containsKey(endId)) {
				graphsonObjectsById.get(startId).descriptors.addAll(descriptors.get(endId));
			} else if (type.equals("RELATED_TO") && graphsonObjectsById.containsKey(startId) && graphsonObjectsById.containsKey(endId)) {
				String label = edgeProperties.get("label").toString();
				RelatedTo relatedToOut = new RelatedTo();
				relatedToOut.label = label;
				relatedToOut.targetId = endId;
				graphsonObjectsById.get(startId).relatedTo.add(relatedToOut);
			}
		}
		
		return graphsonObjectsById;
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
			for (RelatedTo relation : obj.relatedTo) {
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
	
	// Class to represent GraphSON Object structure
	public static class GraphsonObject {
		public String id;
		public String className;
		public List<Descriptor> descriptors;
		public List<RelatedTo> relatedTo;
		
		@Override
		public String toString() {
			StringBuilder toReturn = new StringBuilder();
			toReturn.append(id).append("(").append(className).append(")");
			for (RelatedTo relationship : relatedTo) {
				toReturn.append("\n\t").append(relationship.toString());
			}
			 return toReturn.toString();
		}
	}
	
	// Class to represent a Descriptor in GraphSON
	public static class Descriptor {
		public String name;
		public String value;
	}
	
	// Class to represent RELATED_TO relationships in GraphSON
	public static class RelatedTo {
		public String label;
		public String targetId;
		
		@Override
		public String toString() {
			return "-[" + label + "]->(" + targetId + ")";
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