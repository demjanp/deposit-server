package deposit.service.data;

import deposit.common.ExceptionHandler;

import java.util.*;

public class DataMatchQueryBuilder {

    public static DataMatchQueryBuilderResult buildQuery(Map<String, Object> data, boolean matchEmpty) {
        StringBuilder queryStr = new StringBuilder();
        Map<String, Object> values = new HashMap<>();
        Map<String, Map<String, Object>> objects = extractObjectData(data);
        List<String> objVars = new ArrayList<>();

        Map<String, Map<String, Object>> descriptorsMap = new HashMap<>();

        queryStr.append("MATCH (ns:Namespace {name: $namespaceName})");
        buildObjectMatches(queryStr, objVars, objects, descriptorsMap, matchEmpty);
        buildReturnClause(queryStr, objVars);

        values.put("descriptorsMap", descriptorsMap);

        return new DataMatchQueryBuilderResult(queryStr.toString(), values);
    }

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
            if (label.equals("Object")) {
                objects.put(nodeId, new HashMap<>(Map.of(
                    "class", "",
                    "descriptors", new HashMap<String, Object>(),
                    "relationships", new HashMap<String, String>()
                )));
            } else if (label.equals("Class")) {
                Map<String, Object> properties = (Map<String, Object>) node.get("properties");
                classes.put(nodeId, properties.get("name").toString());
            } else if (label.equals("Descriptor")) {
                Map<String, Object> properties = (Map<String, Object>) node.get("properties");
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
                Map<String, String> objectRelationships = (Map<String, String>) objects.get(startId).get("relationships");
                objectRelationships.put(label, endId);
            }
        }
        return objects;
    }

    private static void buildObjectMatches(StringBuilder queryStr, List<String> objVars,
                                           Map<String, Map<String, Object>> objects,
                                           Map<String, Map<String, Object>> descriptorsMap, boolean matchEmpty) {
        for (Map.Entry<String, Map<String, Object>> objEntry : objects.entrySet()) {
            String objId = objEntry.getKey();
            Map<String, Object> objData = objEntry.getValue();
            String className = (String) objData.get("class");
            if (className == null || className.isEmpty()) {
                ExceptionHandler.throwException("Cannot match an Object without a specified Class. Object ID: " + objId);
            }
            String objVar = "obj_" + objId;
            objVars.add(objVar);

            StringBuilder pattern = new StringBuilder();
            pattern.append(String.format("\nMATCH (%s:Object)-[:MEMBER_OF]->(:Class {name: '%s'})", objVar, className));

            Map<String, Object> descriptors = (Map<String, Object>) objData.get("descriptors");
            if (!descriptors.isEmpty()) {
                Map<String, Object> objDescriptorMap = new HashMap<>();
                descriptorsMap.put(objVar, objDescriptorMap);

                List<String> descriptorConditions = new ArrayList<>();
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
                pattern.append("\nWHERE ").append(String.join(" AND ", descriptorConditions));
            }

            Map<String, String> relationships = (Map<String, String>) objData.get("relationships");
            for (Map.Entry<String, String> relEntry : relationships.entrySet()) {
                String label = relEntry.getKey();
                String tgtId = relEntry.getValue();
                String tgtVar = "obj_" + tgtId;
                pattern.append(String.format("\nMATCH (%s)-[:RELATED_TO {label: '%s'}]->(%s)", objVar, label, tgtVar));
            }

            queryStr.append(pattern);
        }
    }

    private static void buildReturnClause(StringBuilder queryStr, List<String> objVars) {
        queryStr.append("\nRETURN DISTINCT ").append(String.join(", ", objVars));
    }

    public static class DataMatchQueryBuilderResult {
        private final String queryStr;
        private final Map<String, Object> values;

        public DataMatchQueryBuilderResult(String queryStr, Map<String, Object> values) {
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
