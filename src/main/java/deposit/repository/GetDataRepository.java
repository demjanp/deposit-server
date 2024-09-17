package deposit.repository;

import deposit.common.result.MapResult;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
		
		try {
			/* - If ids of root Objects and/or Classes are provided as rootIds, 
			 *   match only nodes related to those root nodes.
			 * - When traversing the node tree, Object nodes of the same Class 
			 *   are not traversed more than once; their relationships are 
			 *   returned but not used for further traversal. Class, Descriptor 
			 *   and DResource nodes are not used for traversal and only 
			 *   returned if they are related to the matched Object nodes.
			 */
			String query = "";
			if (rootIds.isEmpty()) {
				query += """
					MATCH (ns:Namespace {name: $namespaceName})-[:CONTAINS]->(node:Class|Object)
					OPTIONAL MATCH (node)-[rel1]->(:Class|Object|Descriptor|DResource)
					OPTIONAL MATCH (node)-[:HAS_DESCRIPTOR]->(descriptor)
					OPTIONAL MATCH (descriptor)-[rel2:HAS_RESOURCE]->(res:DResource)
					WITH COLLECT(DISTINCT node) + COLLECT(DISTINCT descriptor) + COLLECT(DISTINCT res) AS nodes, 
						 COLLECT(DISTINCT rel1) + COLLECT(DISTINCT rel2) AS rels,
						 COLLECT(DISTINCT res.storedFilename) AS resources
				""";
			} else {
				query += """
					MATCH (ns:Namespace {name: $namespaceName})
					OPTIONAL MATCH (ns)-[:CONTAINS]->(rootClass:Class)
					WHERE rootClass.id IN $rootIds
					OPTIONAL MATCH (ns)-[:CONTAINS]->(rootObject:Object)
					WHERE rootObject.id IN $rootIds
					OPTIONAL MATCH (rootMember:Object)-[:MEMBER_OF]->(rootClass)
					WITH COLLECT(DISTINCT rootObject) + COLLECT(DISTINCT rootMember) AS rootObjects

					CALL {
					  WITH rootObjects
					  CALL apoc.path.expandConfig(
						rootObjects,
						{
						  relationshipFilter: 'RELATED_TO',
						  minLevel: 1,
						  bfs: false,
						  uniqueness: 'NODE_GLOBAL',
						  labelFilter: '-Class'
						}
					  ) YIELD path
					  WITH path,
						   [obj IN nodes(path) | head([(obj)-[:MEMBER_OF]->(class:Class) | class.name])] AS classesEncountered
					  WITH path, classesEncountered,
						   REDUCE(
							 visited = [],
							 className IN classesEncountered |
							   CASE
								 WHEN className IN visited THEN visited
								 ELSE visited + className
							   END
						   ) AS trackedClasses
					  WITH DISTINCT nodes(path)[..size(trackedClasses)] AS traversedObjects
					  RETURN apoc.coll.flatten(COLLECT(traversedObjects)) AS traversedObjects
					}

					WITH apoc.coll.toSet(rootObjects + traversedObjects) AS allObjects
					UNWIND allObjects AS obj

					OPTIONAL MATCH (obj)-[relM:MEMBER_OF]->(cls:Class)
					OPTIONAL MATCH (obj)-[relD:HAS_DESCRIPTOR]->(descr)
					OPTIONAL MATCH (descr)-[relR:HAS_RESOURCE]->(res:DResource)
					OPTIONAL MATCH (cls)-[relCD:HAS_DESCRIPTOR]->(clsDescr)
					WITH obj, cls, descr, res, clsDescr, relM, relD, relR, relCD, res.storedFilename AS resource

					WITH
					  COLLECT(DISTINCT obj) AS objList,
					  COLLECT(DISTINCT cls) AS clsList,
					  COLLECT(DISTINCT descr) AS descrList,
					  COLLECT(DISTINCT res) AS resList,
					  COLLECT(DISTINCT clsDescr) AS clsDescrList,
					  COLLECT(DISTINCT relM) + COLLECT(DISTINCT relD) + COLLECT(DISTINCT relR) + COLLECT(DISTINCT relCD) AS rels,
					  COLLECT(DISTINCT resource) AS resources

					UNWIND clsList AS cls
					OPTIONAL MATCH (cls)-[relCls:RELATED_TO]-(otherCls)
					WHERE otherCls IN clsList

					WITH
					  objList,
					  clsList,
					  clsDescrList,
					  descrList,
					  resList,
					  resources,
					  rels,
					  COLLECT(DISTINCT relCls) AS relClsList

					WITH
					  objList,
					  clsList,
					  clsDescrList,
					  descrList,
					  resList,
					  resources,
					  rels + relClsList AS relationships

					WITH
					  objList + clsList + clsDescrList + descrList AS nodes,
					  relationships,
					  resources
				""";
			}
			query += """
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
			System.out.println(query); // DEBUG
			Result result = tx.execute(query, Map.of("namespaceName", namespaceName, "rootIds", rootIds));
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
