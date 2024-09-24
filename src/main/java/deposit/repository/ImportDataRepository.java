package deposit.repository;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;
import java.util.*;

public class ImportDataRepository extends BaseRepository {

	private static ImportDataRepository instance;
	
	private ImportDataRepository() {}
	
	public static synchronized ImportDataRepository getInstance() {
		if (instance == null) {
			instance = new ImportDataRepository();
		}
		return instance;
	}
	
	public void importData(Transaction tx, ZonedDateTime timeStamp, String username, 
		String namespaceName, String versioningType, Map<String, Object> data, 
		List<String> unique, Map<String, String> overwrite, boolean matchEmpty) {
		
		Set<String> topLevelNodes = Set.of("Class", "Object", "UserTool", "Archive");
		StringBuilder query = new StringBuilder();
		Map<String, Object> params = new HashMap<>();
		Set<String> objectIds = new HashSet<>();
		
		query.append("CREATE (ns:TempNamespace {id: $nsId})\n");
		params.put("nsId", String.valueOf(UUID.randomUUID()));
		
		Map<String, Object> graph = (Map<String, Object>) data.get("graph");
		if (graph == null) {
			throwException("Data must contain 'graph'.");
		}
		List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
		List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");
		if (nodes == null || edges == null) {
			throwException("Graph must contain 'nodes' and 'edges'.");
		}

		for (Map<String, Object> node : nodes) {
			String label = node.get("label").toString();
			String nodeId = node.get("id").toString();
			Map<String, Object> properties = (Map<String, Object>) node.getOrDefault("properties", Map.of("id", UUID.randomUUID()));
			String nodeVar = "n_" + nodeId;
			String propsVar = "p_" + nodeId;
			String prefix = topLevelNodes.contains(label) ? "(ns)-[:CONTAINS]->" : "";
			query.append(String.format("CREATE %s(%s:%s) SET %s = $%s\n", prefix, nodeVar, label, nodeVar, propsVar));
			params.put(propsVar, properties);
			if (label.equals("Object")) {
				objectIds.add(properties.get("id").toString());
			}
		}
		
		for (Map<String, Object> edge : edges) {
			String edgeId = edge.get("id").toString();
			String edgeVar = "e_" + edgeId;
			String startVar = "n_" + edge.get("outV").toString();
			String endVar = "n_" + edge.get("inV").toString();
			String type = edge.get("type").toString();
			String propsVar = "pe_" + edgeId;
			Map<String, Object> properties = (Map<String, Object>) edge.getOrDefault("properties", Map.of("id", UUID.randomUUID()));
			query.append(String.format("CREATE (%s)-[%s:%s]->(%s) SET %s = $%s\n", startVar, edgeVar, type, endVar, edgeVar, propsVar));
			params.put(propsVar, properties);
		}
		
		System.out.print(query); // DEBUG
		
		try {
			tx.execute(query.toString(), params);
		} catch (Exception e) {
			throwException("Error importing data.", e);
		}
		
		Map<String, String> objectIdMap = new HashMap<>();
		for (String tempObjectId : objectIds) {
			// TODO :
			// 	objectData = GetDataRepository.getData(tx, namespaceName, List.of(tempObjectId))
			//  DataHandler.DataMatchQuery matchQuery = DataHandler.buildDataMatchQuery(objectData, matchEmpty);
			//  StringMapResult result = GetObjectsByDataRepository.getObjectsByData(tx, namespaceName, matchQuery.getQueryStr(), matchQuery.getValues());
			//	Map<String, String> tempObjectIdMap = result.data; // {origId: matchedId, ...}
			//	if tempObjectId in tempObjectIdMap:
			//		objectIdMap[tempObjectId] = matchedId
			//	else:
			//		create new Object & Descriptors
			//		objectIdMap[tempObjectId] = createdObjectId
		}
		// TODO : 
		//	create relationships between Objects in active Namespace based on objectIdMap where at least one end is a newly created node
		//	delete TempNamespace & its subtree
	}
}
