package deposit.service.data;

import deposit.common.ExceptionHandler;

import java.util.*;

public class DataHandler {

	/* buildDataMatchQuery
	 *
	 * 	Accepts:
	 *	data Map<String, Object> = {
	 * 		nodes List<Map<String, Object>> = {
	 * 			id Long = nodeId,
	 * 			label String = nodeLabel,
	 * 			properties Map<String, Object> = {name: value, ...}
	 *		},
	 * 		edges List<Map<String, Object>> = {
	 * 			id Long = edgeId,
	 * 			type String = edgeType,
	 * 			outV Long = startNodeId,
	 * 			inV Long = endNodeId,
	 * 			properties Map<String, Object> = {name: value, ...}
	 * 		}
	 *	}
	 * 	matchEmpty boolean = if true, ensure all Descriptors of the matched Objects are present and equal those in data.
	 * 		Otherwise only match Descriptors present in the Namespace and with non-null values in the data.
	 *
	 *	Returns:
	 *	result DataMatchQuery
	 *		result.getQueryStr() String = query string
	 * 		result.getValues() Map<String, Object> = query parameters
	 */
	public static DataMatchQuery buildDataMatchQuery(Map<String, Object> data, boolean matchEmpty) {
		StringBuilder queryStr = new StringBuilder();
		Map<String, Object> values = new HashMap<>();
		Map<String, Map<String, Object>> objects = extractObjectData(data);
		List<String> objVars = new ArrayList<>();

		Map<String, Map<String, Object>> descriptorsMap = new HashMap<>();
		Map<String, String> objVarToOrigId = new HashMap<>();

		queryStr.append("MATCH (ns:Namespace {name: $namespaceName})");
		buildObjectMatches(queryStr, objVars, objects, descriptorsMap, matchEmpty, objVarToOrigId);
		buildReturnClause(queryStr, objVars, objVarToOrigId);

		values.put("descriptorsMap", descriptorsMap);

		return new DataMatchQuery(queryStr.toString(), values);
	}

	/* extractObjectData
	 *
	 * 	Accepts:
	 *	data Map<String, Object> = same as buildDataMatchQuery
	 *
	 *	Returns:
	 *	objects Map<String, Map<String, Object>> = {
	 *		nodeId: {
	 *			id String = objectId,
	 *			class String = className,
	 *			descriptors Map<String, Object> = {name: value, ...},
	 *			relationships Map<String, List<String>> = {label: [objId, ...], ...}
	 *		}
	 *	}
	 */
	private static Map<String, Map<String, Object>> extractObjectData(Map<String, Object> data) {
		Map<String, Map<String, Object>> objects = new HashMap<>();
		Map<String, String> classes = new HashMap<>();
		Map<String, List<Object>> descriptors = new HashMap<>();

		List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
		List<Map<String, Object>> edges = (List<Map<String, Object>>) data.get("edges");

		if (nodes == null || edges == null) {
			ExceptionHandler.throwException("Data must contain 'nodes' and 'edges'.");
		}

		for (Map<String, Object> node : nodes) {
			String label = node.get("label").toString();
			String nodeId = node.get("id").toString();
			Map<String, Object> properties = (Map<String, Object>) node.getOrDefault("properties", null);
			if (label.equals("Object")) {
				objects.put(nodeId, new HashMap<>(Map.of(
					"id", properties.containsKey("id") ? properties.get("id").toString() : nodeId,
					"class", "",
					"descriptors", new HashMap<String, Object>(),
					"relationships", new HashMap<String, List<String>>()
				)));
			} else if (label.equals("Class")) {
				classes.put(nodeId, properties.get("name").toString());
			} else if (label.equals("Descriptor")) {
				if (properties.containsKey("value")) {
					descriptors.put(nodeId, List.of(properties.get("name").toString(), properties.get("value")));
				}
			}
		}
		
		for (Map<String, Object> edge : edges) {
			String startId = edge.get("outV").toString();
			String endId = edge.get("inV").toString();
			String type = edge.get("type").toString();

			if (type.equals("MEMBER_OF") && objects.containsKey(startId) && classes.containsKey(endId)) {
				objects.get(startId).put("class", classes.get(endId));

			} else if (type.equals("HAS_DESCRIPTOR") && objects.containsKey(startId) && descriptors.containsKey(endId)) {
				String name = descriptors.get(endId).get(0).toString();
				Object value = descriptors.get(endId).get(1);
				Map<String, Object> objectDescriptors = (Map<String, Object>) objects.get(startId).get("descriptors");
				objectDescriptors.put(name, value);

			} else if (type.equals("RELATED_TO") && objects.containsKey(startId) && objects.containsKey(endId)) {
				Map<String, Object> properties = (Map<String, Object>) edge.get("properties");
				String label = properties.get("label").toString();
				Map<String, List<String>> objectRelationships = (Map<String, List<String>>) objects.get(startId).get("relationships");
				objectRelationships.computeIfAbsent(label, k -> new ArrayList<>()).add(endId);
			}
		}
		return objects;
	}
		
	private static void buildObjectMatches(StringBuilder queryStr, List<String> objVars,
										   Map<String, Map<String, Object>> objects,
										   Map<String, Map<String, Object>> descriptorsMap, boolean matchEmpty,
										   Map<String, String> objVarToOrigId) {
		for (Map.Entry<String, Map<String, Object>> objEntry : objects.entrySet()) {
			String objId = objEntry.getKey();
			Map<String, Object> objData = objEntry.getValue();
			String className = (String) objData.get("class");
			if (className == null || className.isEmpty()) {
				ExceptionHandler.throwException("Cannot match an Object without a specified Class. Object ID: " + objId);
			}
			String origId = (String) objData.get("id");
			String objVar = "obj_" + objId;
			objVars.add(objVar);

			objVarToOrigId.put(objVar, origId);

			StringBuilder pattern = new StringBuilder();
			List<String> descriptorConditions = new ArrayList<>();
			pattern.append(String.format("\nMATCH (ns)-[:CONTAINS]->(%s:Object)-[:MEMBER_OF]->(:Class {name: '%s'})", objVar, className));
			Map<String, Object> descriptors = (Map<String, Object>) objData.get("descriptors");

			if (matchEmpty) {
				String objVarsStr = String.join(", ", objVars);
				pattern.append(String.format("\nOPTIONAL MATCH (%s)-[:HAS_DESCRIPTOR]->(dAll_%s)", objVar, objId));
				pattern.append(String.format("\nWITH %s, count(dAll_%s) AS totalDescriptors_%s", objVarsStr, objId, objId));
				descriptorConditions.add(String.format("totalDescriptors_%s = %d", objId, descriptors.size()));
			}

			if (!descriptors.isEmpty()) {
				Map<String, Object> objDescriptorMap = new HashMap<>();
				descriptorsMap.put(objVar, objDescriptorMap);

				for (Map.Entry<String, Object> descrEntry : descriptors.entrySet()) {
					String descrName = descrEntry.getKey();
					Object descrValue = descrEntry.getValue();

					objDescriptorMap.put(descrName, descrValue);

					if (matchEmpty) {
						// Ensure the object has the descriptor with the specified value
						descriptorConditions.add(String.format("(%s)-[:HAS_DESCRIPTOR]->(:Descriptor {name: '%s', value: $descriptorsMap[%s][%s]})",
								objVar, descrName, "'" + objVar + "'", "'" + descrName + "'"));
					} else {
						// Ensure that either the descriptor is not present or has the specified value
						descriptorConditions.add(String.format("(NOT EXISTS { MATCH (%s)-[:HAS_DESCRIPTOR]->(d:Descriptor {name: '%s'}) WHERE d.value <> $descriptorsMap[%s][%s] })",
								objVar, descrName, "'" + objVar + "'", "'" + descrName + "'"));
					}
				}
			}
			if (!descriptorConditions.isEmpty()) {
				pattern.append("\nWHERE ").append(String.join(" AND ", descriptorConditions));
			}

			Map<String, List<String>> relationships = (Map<String, List<String>>) objData.get("relationships");
			for (Map.Entry<String, List<String>> relEntry : relationships.entrySet()) {
				String label = relEntry.getKey();
				for (String tgtId : relEntry.getValue()) {
					String tgtVar = "obj_" + tgtId;
					pattern.append(String.format("\nMATCH (%s)-[:RELATED_TO {label: '%s'}]->(%s)", objVar, label, tgtVar));
				}
			}

			queryStr.append(pattern);
		}
	}

	private static void buildReturnClause(StringBuilder queryStr, List<String> objVars, Map<String, String> objVarToOrigId) {
		List<String> mappingItems = new ArrayList<>();
		for (String objVar : objVars) {
			String origId = objVarToOrigId.get(objVar);
			mappingItems.add(String.format("`%s`: %s.id", origId, objVar));
		}
		String mappingsStr = String.join(", ", mappingItems);
		queryStr.append("\nRETURN {").append(mappingsStr).append("} AS data");
	}

	public static class DataMatchQuery {
		private final String queryStr;
		private final Map<String, Object> values;

		public DataMatchQuery(String queryStr, Map<String, Object> values) {
			this.queryStr = queryStr;
			this.values = values;
		}

		public String getQueryStr() {
			return queryStr;
		}

		public Map<String, Object> getValues() {
			return values;
		}
	}
}
