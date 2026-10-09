package deposit.repository;

import deposit.common.PatternData;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GetObjectsByPatternRepository extends BaseRepository {

	private static GetObjectsByPatternRepository instance;

	private GetObjectsByPatternRepository() {}

	public static synchronized GetObjectsByPatternRepository getInstance() {
		if (instance == null) {
			instance = new GetObjectsByPatternRepository();
		}
		return instance;
	}

	public Stream<Map<String, String>> getObjectsByPattern(Transaction tx, String namespaceName, PatternData.Pattern pattern, Map<String, Object> data) {
		if (pattern.objects.isEmpty()) {
			throwException("Cannot match a Pattern with no objects");
		}

		StringBuilder query = new StringBuilder();
		Map<String, Object> params = new HashMap<>();
		List<Map<String, Object>> objectInfoList = new ArrayList<>();

		for (PatternData.PObject obj : pattern.objects.values()) {
			List<Map<String, Object>> givenDescriptors = new ArrayList<>();
			Map<String, Object> descriptorValues = (Map<String, Object>) data.getOrDefault(obj.name, Map.of());
			for (PatternData.PDescriptor descriptor : obj.descriptors) {
				Map<String, Object> descrMap = new HashMap<>();
				descrMap.put("name", descriptor.name);
				descrMap.put("value", descriptorValues.get(descriptor.name));
				givenDescriptors.add(descrMap);
			}

			Map<String, Object> objectInfo = new HashMap<>();
			objectInfo.put("className", obj.className);
			objectInfo.put("descriptors", givenDescriptors);
			objectInfo.put("objectName", obj.name);
			objectInfo.put("relationships", obj.relationships.stream().map(rel -> Map.of("label", rel.label, "targetName", rel.targetName)).collect(Collectors.toList()));
			objectInfoList.add(objectInfo);
		}

		query.append("MATCH (ns:Namespace {name: $namespaceName})\n");
		params.put("namespaceName", namespaceName);
		query.append("WITH ns, $objectInfoList AS objectInfoList\n");
		params.put("objectInfoList", objectInfoList);
		query.append("UNWIND objectInfoList AS objectInfo\n");
		query.append("MATCH (ns)-[:CONTAINS]->(obj:Object)-[:MEMBER_OF]->(:Class {name: objectInfo.className})\n");

		query.append("WITH obj, objectInfo\n");
		query.append("UNWIND objectInfo.descriptors AS descriptor\n");
		query.append("OPTIONAL MATCH (obj)-[:HAS_DESCRIPTOR]->(d:Descriptor)\n");
		query.append("WHERE d.name = descriptor.name AND d.value = descriptor.value\n");

		query.append("WITH obj, objectInfo\n");
		query.append("UNWIND objectInfo.relationships AS rel\n");
		query.append("OPTIONAL MATCH (obj)-[r:`").append("\" + rel.label + \"")
			  .append("`]->(related:Object)-[:MEMBER_OF]->(:Class {name: rel.targetName})\n");

		query.append("WITH objectInfo.objectName AS objectName, collect(DISTINCT obj.id) + collect(DISTINCT related.id) AS ids\n");
		query.append("RETURN apoc.map.fromPairs(collect([objectName, ids])) AS results\n");

		Result result = tx.execute(query.toString(), params);

		return result.stream().map(record -> record.entrySet().stream()
				.collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toString())));
	}
}
