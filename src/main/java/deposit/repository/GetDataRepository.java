package deposit.repository;

import deposit.common.FileSystem;
import deposit.common.result.MapResult;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.util.*;

public class GetDataRepository extends BaseRepository {

	private static GetDataRepository instance;
	
	private GetDataRepository() {}
	
	public static synchronized GetDataRepository getInstance() {
		if (instance == null) {
			instance = new GetDataRepository();
		}
		return instance;
	}
	
	public MapResult getData(Transaction tx, String namespaceName, List<String> rootIds) {
		Map<String, Object> data = new HashMap<>(Map.of(
				"graph", Map.of(
						"nodes", List.of(),
						"edges", List.of()
				),
				"resources", List.of()
		));
		String finalQuery = """
			WITH [node IN nodes WHERE node IS NOT NULL | {
				id: id(node),
				label: head(labels(node)),
				properties: properties(node)
			}] AS nodes,
			[rel IN relationships WHERE rel IS NOT NULL | {
				id: id(rel),
				type: type(rel),
				outV: id(startNode(rel)),
				inV: id(endNode(rel)),
				properties: properties(rel)
			}] AS edges,
			resources
			RETURN { nodes: nodes, edges: edges } AS graph, resources
		""";
		try {
			if (rootIds.isEmpty()) {
				String query = """
					MATCH (ns:Namespace {name: $namespaceName})-[:CONTAINS]->(node:Class|Object)
					OPTIONAL MATCH (node)-[rel1]->(:Class|Object|Descriptor|DResource)
					OPTIONAL MATCH (node)-[:HAS_DESCRIPTOR]->(descriptor)
					OPTIONAL MATCH (descriptor)-[rel2:HAS_RESOURCE]->(res:DResource)
					WITH COLLECT(DISTINCT node) + COLLECT(DISTINCT descriptor) + COLLECT(DISTINCT res) AS nodes,
						 COLLECT(DISTINCT rel1) + COLLECT(DISTINCT rel2) AS relationships,
						 COLLECT(DISTINCT res.storedFilename) AS resources
				""" + finalQuery;
				Result result = tx.execute(query, Map.of("namespaceName", namespaceName));
				if (result.hasNext()) {
					Map<String, Object> resultData = result.next();
					data.put("graph", resultData.get("graph"));
					data.put("resources", resultData.get("resources"));
				}
			} else {
				String query = """
					MATCH (obj:Object)-[:MEMBER_OF]->(cls:Class) WHERE obj.id IN $seedIds OR cls.id IN $seedIds
					RETURN obj.id AS objectId, cls.name AS className
				""";
				Set<String> seedIds = new HashSet<>();
				Set<String> foundClasses = new HashSet<>();
				Result result = tx.execute(query, Map.of("seedIds", rootIds));
				while (result.hasNext()) {
					Map<String, Object> record = result.next();
					String objectId = record.get("objectId").toString();
					String className = record.get("className").toString();
					seedIds.add(objectId);
					foundClasses.add(className);
				}
				if (seedIds.isEmpty()) {
					return new MapResult(data);
				}
				Set<String> foundIds = new HashSet<>(seedIds);

				query = """
					OPTIONAL MATCH (objSrc:Object) WHERE objSrc.id IN $seedIds
					OPTIONAL MATCH (objSrc)-[:RELATED_TO]-(objTgt)-[:MEMBER_OF]->(clsTgt:Class), (objSrc)-[:MEMBER_OF]->(clsSrc:Class)
					WHERE clsSrc.name <> clsTgt.name
					RETURN objTgt.id AS objectId, clsTgt.name AS className
				""";
				while (!seedIds.isEmpty()) {
					result = tx.execute(query, Map.of("seedIds", seedIds));
					seedIds = new HashSet<>();
					Set<String> newClasses = new HashSet<>();
					while (result.hasNext()) {
						Map<String, Object> record = result.next();
						String objectId = record.get("objectId").toString();
						String className = record.get("className").toString();
						if (!foundClasses.contains(className)) {
							seedIds.add(objectId);
							newClasses.add(className);
						}
					}
					foundIds.addAll(seedIds);
					foundClasses.addAll(newClasses);
				}

				query = """
					MATCH (obj:Object) WHERE obj.id IN $foundIds
					OPTIONAL MATCH (obj)-[:MEMBER_OF]->(cls:Class)
					OPTIONAL MATCH (obj)-[:HAS_DESCRIPTOR]->(descr)
					OPTIONAL MATCH (descr)-[:HAS_RESOURCE]->(res:DResource)
					OPTIONAL MATCH (cls)-[:HAS_DESCRIPTOR]->(clsDescr)
					WITH obj, cls, descr, res, clsDescr, res.storedFilename AS resource
					WITH
						COLLECT(DISTINCT obj) AS objList,
						COLLECT(DISTINCT cls) AS clsList,
						COLLECT(DISTINCT descr) AS descrList,
						COLLECT(DISTINCT res) AS resList,
						COLLECT(DISTINCT clsDescr) AS clsDescrList,
						COLLECT(DISTINCT resource) AS resources
					WITH objList + clsList + descrList + clsDescrList + resList AS nodes, resources
					UNWIND nodes AS node
					OPTIONAL MATCH (node)-[rel]-(tgtNode) WHERE tgtNode IN nodes
					WITH nodes, resources, COLLECT(DISTINCT rel) AS relationships
				""" + finalQuery;
				result = tx.execute(query, Map.of("namespaceName", namespaceName, "foundIds", foundIds));
				if (result.hasNext()) {
					Map<String, Object> resultData = result.next();
					data.put("graph", resultData.get("graph"));
					data.put("resources", resultData.get("resources"));
				}
			}

		} catch (Exception e) {
			throwException("Error retrieving data.", e);
		}
		
		for (String filename : (List<String>) data.get("resources")) {
			FileSystem.copyToTransient(filename);
		}
		
		return new MapResult(data);
	}
}
