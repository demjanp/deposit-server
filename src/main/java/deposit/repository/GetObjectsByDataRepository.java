package deposit.repository;

import deposit.common.GraphSONData;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class GetObjectsByDataRepository extends BaseRepository {

	private static GetObjectsByDataRepository instance;
	
	private GetObjectsByDataRepository() {}
	
	public static synchronized GetObjectsByDataRepository getInstance() {
		if (instance == null) {
			instance = new GetObjectsByDataRepository();
		}
		return instance;
	}
	
	public Map<String, List<String>> getObjectsByData(Transaction tx, String namespaceName, 
			List<GraphSONData.GraphsonObject> graphSONObjects, List<String> overwrite, boolean matchEmpty) {
		
		Map<String, List<String>> objectIdMap = new HashMap<>();
		StringBuilder query = new StringBuilder();
		Map<String, Object> params = new HashMap<>();
		
		List<Map<String, Object>> objectInfoList = new ArrayList<>();
		for (GraphSONData.GraphsonObject graphSONObject : graphSONObjects) {
			if (overwrite.contains(graphSONObject.originalId)) {
				objectIdMap.put(graphSONObject.id, List.of(graphSONObject.originalId));
				continue;
			}
			if (graphSONObject.className == null || graphSONObject.className.isEmpty()) {
				throwException("Cannot match an Object without a specified Class.");
			}
			List<Map<String, Object>> givenDescriptors = new ArrayList<>();
			for (GraphSONData.Descriptor descriptor : graphSONObject.descriptors) {
				Map<String, Object> descrMap = new HashMap<>();
				descrMap.put("name", descriptor.name);
				descrMap.put("value", descriptor.value);
				givenDescriptors.add(descrMap);
			}
			Map<String, Object> objectInfo = new HashMap<>();
			objectInfo.put("className", graphSONObject.className);
			objectInfo.put("descriptors", givenDescriptors);
			objectInfo.put("dataId", graphSONObject.id);
			if (matchEmpty) {
				objectInfo.put("descriptorCount", graphSONObject.descriptors.size());
			}
			objectInfoList.add(objectInfo);
		}
		
		// Match Namespace and Class
		query.append("MATCH (ns:Namespace {name: $namespaceName})\n");
		params.put("namespaceName", namespaceName);
		query.append("WITH ns, $objectInfoList AS objectInfoList\n");
		params.put("objectInfoList", objectInfoList);
		query.append("UNWIND objectInfoList AS objectInfo\n");
		query.append("MATCH (ns)-[:CONTAINS]->(obj:Object)-[:MEMBER_OF]->(:Class {name: objectInfo.className})\n");
		
		// Match Descriptors
		if (matchEmpty) {
			query.append("""
				WITH objectInfo, obj
				UNWIND objectInfo.descriptors AS givenDescr
				MATCH (obj)-[:HAS_DESCRIPTOR]->(d:Descriptor {name: givenDescr.name})
				WHERE (d.value IS NULL AND givenDescr.value IS NULL) OR d.value = givenDescr.value
				WITH obj, objectInfo, COUNT(d) AS matchingDescriptorCount
				MATCH (obj)-[:HAS_DESCRIPTOR]->(allDescriptors:Descriptor)
				WITH obj, objectInfo, matchingDescriptorCount, COUNT(allDescriptors) AS totalDescriptorCount
				WHERE matchingDescriptorCount = objectInfo.descriptorCount AND totalDescriptorCount = objectInfo.descriptorCount
				WITH objectInfo.dataId AS dataId, collect(DISTINCT obj.id) AS ids
				WITH collect({dataId: dataId, ids: ids}) AS dataIdIdsList
				""");
		} else {
			query.append("""
				UNWIND objectInfo.descriptors AS givenDescr
				OPTIONAL MATCH (obj)-[:HAS_DESCRIPTOR]->(d:Descriptor {name: givenDescr.name})
				WITH objectInfo, obj, givenDescr, d
				WITH objectInfo, obj, SUM(CASE 
					WHEN d IS NULL THEN 0
					WHEN d.value = givenDescr.value OR (d.value IS NULL AND givenDescr.value IS NULL) THEN 1
					ELSE -1
				END) AS matchScore, COUNT(d) AS matchedDescriptors
				WHERE matchScore >= 0
				WITH objectInfo.dataId AS dataId, obj, matchedDescriptors
				ORDER BY dataId, matchedDescriptors DESC
				WITH dataId, collect({objId: obj.id, matchedDescriptors: matchedDescriptors}) AS objMatches
				WITH dataId, [om IN objMatches WHERE om.matchedDescriptors = head(objMatches).matchedDescriptors | om.objId] AS ids
				WITH collect({dataId: dataId, ids: ids}) AS dataIdIdsList
				""");
		}
		query.append("WITH reduce(resultMap = {}, pair IN dataIdIdsList | apoc.map.setKey(resultMap, pair.dataId, pair.ids)) AS descrResults\n");
		
		// Match Relationships
		query.append("MATCH (ns:Namespace {name: $namespaceName})\n");
		for (GraphSONData.GraphsonObject graphSONObject : graphSONObjects) {
			String dataId = graphSONObject.id;
			query.append(String.format("MATCH (ns)-[:CONTAINS]->(obj%s:Object) WHERE obj%s.id IN descrResults[\"%s\"]\n", dataId, dataId, dataId));
		}

		List<String> relationshipMatchItems = new ArrayList<>();
		for (GraphSONData.GraphsonObject graphSONObject : graphSONObjects) {
			if (!graphSONObject.relationships.isEmpty()) {
				String dataId = graphSONObject.id;
				int index = 1;
				for (GraphSONData.Relationship relationship : graphSONObject.relationships) {
					String labelVar = "label" + dataId + "_" + index;
					String targetId = relationship.targetId;
					relationshipMatchItems.add(String.format("(obj%s)-[:RELATED_TO {label: $%s}]->(obj%s:Object)", dataId, labelVar, targetId));
					params.put(labelVar, relationship.label);
					index++;
				}
			}
		}
		if (!relationshipMatchItems.isEmpty()) {
			query.append("MATCH ").append(String.join(",\n", relationshipMatchItems)).append("\n");
		}
		
		// Collect and return results
		List<String> objCollectItems = new ArrayList<>();
		List<String> objMapItems = new ArrayList<>();
		for (GraphSONData.GraphsonObject graphsonObject : graphSONObjects) {
			String dataId = graphsonObject.id;
			objCollectItems.add(String.format("COLLECT(DISTINCT obj%s.id) AS objIds%s", dataId, dataId));
			objMapItems.add(String.format("\"%s\", objIds%s", dataId, dataId));
		}
		query.append(String.format("WITH %s\n", String.join(", ", objCollectItems)));
		query.append(String.format("RETURN apoc.map.fromValues([%s]) AS results\n", String.join(", ", objMapItems)));
		
		// Execute query
		Result result = tx.execute(query.toString(), params);
		if (result.hasNext()) {
			Map<String, Object> record = result.next();
			Map<String, List<Object>> resultsMap = (Map<String, List<Object>>) record.get("results");
			for (Map.Entry<String, List<Object>> entry : resultsMap.entrySet()) {
				String key = entry.getKey();
				List<String> ids = entry.getValue().stream()
						.map(Object::toString)
						.collect(Collectors.toList());
				if (!ids.isEmpty()) {
					objectIdMap.put(key, ids);
				}
			}
		}

		return objectIdMap;
	}
}