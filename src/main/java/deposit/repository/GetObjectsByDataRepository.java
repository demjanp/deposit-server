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
	
	public Map<String, List<String>> getObjectsByData(Transaction tx, String namespaceName, List<GraphSONData.GraphsonObject> graphSONObjects, boolean matchEmpty) {
		Map<String, List<String>> objectIdMap = new HashMap<>();
		StringBuilder query = new StringBuilder();
		Map<String, Object> params = new HashMap<>();
		
		List<Map<String, Object>> objectInfoList = new ArrayList<>();
		for (GraphSONData.GraphsonObject graphSONObject : graphSONObjects) {
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
			query.append("WITH ns, objectInfo, obj\n");
			query.append("UNWIND objectInfo.descriptors AS givenDescr\n");
			query.append("MATCH (obj)-[:HAS_DESCRIPTOR]->(d:Descriptor {name: givenDescr.name})\n");
			query.append("WHERE (d.value IS NULL AND givenDescr.value IS NULL) OR d.value = givenDescr.value\n");
			query.append("WITH ns, objectInfo, obj, COUNT(d) AS matchingDescriptorCount\n");
			query.append("WHERE matchingDescriptorCount = objectInfo.descriptorCount\n");
			query.append("WITH ns, objectInfo.dataId AS dataId, collect(DISTINCT obj.id) AS ids\n");
			query.append("WITH ns, collect({dataId: dataId, ids: ids}) AS dataIdIdsList\n");
		} else {
			query.append("UNWIND objectInfo.descriptors AS givenDescr\n");
			query.append("OPTIONAL MATCH (obj)-[:HAS_DESCRIPTOR]->(d:Descriptor {name: givenDescr.name, value: givenDescr.value})\n");
			query.append("WITH ns, objectInfo.dataId AS dataId, obj, SUM(CASE WHEN d IS NOT NULL THEN 1 ELSE 0 END) AS matchCount\n");
			query.append("ORDER BY dataId, matchCount DESC\n");
			query.append("WITH ns, dataId, collect({objId: obj.id, matchCount: matchCount}) AS objMatches\n");
			query.append("WITH ns, dataId, [om IN objMatches WHERE om.matchCount = head(objMatches).matchCount | om.objId] AS ids\n");
			query.append("WITH ns, collect({dataId: dataId, ids: ids}) AS dataIdIdsList\n");
		}
		query.append("WITH ns, reduce(resultMap = {}, pair IN dataIdIdsList | apoc.map.setKey(resultMap, pair.dataId, pair.ids)) AS descrResults\n");
		
		// Match Relationships
		for (GraphSONData.GraphsonObject graphSONObject : graphSONObjects) {
			String dataId = graphSONObject.id;
			if (!graphSONObject.relatedTo.isEmpty()) {
				int index = 1;
				for (GraphSONData.RelatedTo relatedTo : graphSONObject.relatedTo) {
					String labelVar = "label" + dataId + "_" + index;
					String targetId = relatedTo.targetId;
					query.append(String.format("MATCH (ns)-[:CONTAINS]->(obj%s:Object)-[:RELATED_TO {label: $%s}]->(obj%s:Object)\n", dataId, labelVar, targetId));
					query.append(String.format("WHERE obj%s.id IN descrResults[\"%s\"] AND obj%s.id IN descrResults[\"%s\"]\n", dataId, dataId, targetId, targetId));
					params.put(labelVar, relatedTo.label);
					index++;
				}
			} else {
				query.append(String.format("MATCH (ns)-[:CONTAINS]->(obj%s:Object)", dataId));
			}
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
				objectIdMap.put(key, ids);
			}
		}

		return objectIdMap;
	}
}