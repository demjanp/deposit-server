package deposit.repository;

import deposit.common.result.MapResult;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.util.*;

public class GetNamespaceDataRepository extends BaseRepository {

	private static GetNamespaceDataRepository instance;
	
	private GetNamespaceDataRepository() {}
	
	public static synchronized GetNamespaceDataRepository getInstance() {
		if (instance == null) {
			instance = new GetNamespaceDataRepository();
		}
		return instance;
	}
	
	public MapResult getNamespaceData(Transaction tx, String namespaceName) {
		Map<String, Object> data = new HashMap<>(Map.of(
				"graph", Map.of(
						"nodes", List.of(),
						"edges", List.of()
				),
				"resources", List.of()
		));
		try {
			String query = """
				MATCH (ns:Namespace {name: $namespaceName})-[*]->(node)
				WITH ns, COLLECT(DISTINCT node) AS nsNodes
				WITH nsNodes + ns AS nodes
				OPTIONAL MATCH (res:DResource) WHERE res IN nodes
				OPTIONAL MATCH (src)-[rel]-(tgt) WHERE src IN nodes AND tgt IN nodes
				WITH nodes, COLLECT(DISTINCT rel) AS relationships, COLLECT(DISTINCT res.storedFilename) AS resources
				WITH [node IN nodes WHERE node IS NOT NULL | {
					id: node.id,
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
			Result result = tx.execute(query, Map.of("namespaceName", namespaceName));
			if (result.hasNext()) {
				Map<String, Object> resultData = result.next();
				data.put("graph", resultData.get("graph"));
				data.put("resources", resultData.get("resources"));
			}
		} catch (Exception e) {
			throwException("Error retrieving data.", e);
		}
		return new MapResult(data);
	}
}
