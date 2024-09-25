package deposit.repository;

import deposit.service.data.DataHandler;

import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

public class ImportDataRepository extends BaseRepository {

	private static ImportDataRepository instance;
	private final GetDataRepository getDataRepository;
	private final GetObjectsByDataRepository getObjectsByDataRepository;
	
	private ImportDataRepository() {
		this.getDataRepository = GetDataRepository.getInstance();
		this.getObjectsByDataRepository = GetObjectsByDataRepository.getInstance();
	}
	
	public static synchronized ImportDataRepository getInstance() {
		if (instance == null) {
			instance = new ImportDataRepository();
		}
		return instance;
	}
	
	public void importData(Transaction tx, ZonedDateTime timeStamp, String username, 
		String namespaceName, String versioningType, Map<String, Object> data, 
		List<String> unique, List<String> overwrite, boolean matchEmpty) {

		// Create TempNamespace and add the imported data to it
		Map<String, Object> nsData = createTempNamespace(tx, data);
		String nsId = nsData.get("nsId").toString();
		Set<String> objectIds = (Set<String>) nsData.get("objectIds");

		// Get Class names of imported Objects
		Map<String, String> objectClassNames = getObjectClassNames(tx, nsId);
		
		Map<String, String> objectIdMap = new HashMap<>(); // maps Object ids in data to ids in the active Namespace
		Map<String, Object> toCreateObjects = new HashMap<>();
		Set<String> classNames = new HashSet<>();
		for (String tempObjectId : objectIds) {
			boolean foundObject = false;

			if (!unique.contains(objectClassNames.get(tempObjectId)) && !overwrite.contains(tempObjectId)) {
				// Get subtree for Object from TempNamespace
				Map<String, Object> tempObjectData = getDataRepository.getData(tx, "", List.of(tempObjectId)).data;
				Map<String, Object> graph = (Map<String, Object>) tempObjectData.get("graph");
				if (graph == null) {
					throwException("Data must contain 'graph'.");
				}
				// Match the subtree in the active Namespace
				DataHandler.DataMatchQuery matchQuery = DataHandler.buildDataMatchQuery(graph, matchEmpty);
				Map<String, String> tempObjectIdMap = getObjectsByDataRepository.getObjectsByData(
						tx, namespaceName, matchQuery.getQueryStr(), matchQuery.getValues()).data;
				if (tempObjectIdMap.containsKey(tempObjectId)) {
					// Object has a match in the active Namespace
					objectIdMap.put(tempObjectId, tempObjectIdMap.get(tempObjectId));
					foundObject = true;
				}
			}

			if (!foundObject) {
				Map<String, Object> objectData = getObjectData(tx, nsId, tempObjectId);
				String className = objectData.get("className").toString();
				classNames.add(className);
				toCreateObjects.put(tempObjectId, Map.of(
						"className", className,
						"properties", objectData.get("objProperties"),
						"descriptors", objectData.get("descriptors")
				));
			}
		}

		System.out.println("toCreateObjects: " + toCreateObjects); // DEBUG

		Map<String, Object> toCreateClasses = new HashMap<>();

		// Check if collected Classes & their Descriptors don't collide with those in the active Namespace
		for (String className : classNames) {
			Map<String, Object> classData = checkClass(tx, nsId, namespaceName, className);
			if (classData != null) {
				toCreateClasses.put(className, classData);
			}
		}

		System.out.println("toCreateClasses: " + toCreateClasses); // DEBUG

		// TODO :
		//	create Classes & their Descriptors from toCreateClasses
		//		for Classes, maintain only name, position & comment properties
		//		for Class Descriptors, maintain only name, type & comment properties
		//  create collected Objects & their Descriptors from toCreateObjects
		//		if objectId is in overwrite, don't create a new Object and delete existing Descriptors before creating new ones
		//		for Objects, maintain only position property
		//		for Descriptors, maintain only name & value properties
		//  	objectIdMap[tempObjectId] = createdObjectId
		//	re-create all Object and Class (? prob. handled by creating object rels) Relationships from TempNamespace
		//		in the active Namespace based on objectIdMap where they don't exist
		//	delete TempNamespace & its subtree
	}

	private Map<String, Object> createTempNamespace(Transaction tx, Map<String, Object> data) {
		Set<String> topLevelNodes = Set.of("Class", "Object", "UserTool", "Archive");
		StringBuilder query = new StringBuilder();
		Map<String, Object> params = new HashMap<>();
		Set<String> objectIds = new HashSet<>();

		String nsId = String.valueOf(UUID.randomUUID());
		query.append("CREATE (ns:TempNamespace {id: $nsId})\n");
		params.put("nsId", nsId);

		Map<String, List<Map<String, Object>>> graph = (Map<String, List<Map<String, Object>>>) data.get("graph");
		if (graph == null) {
			throwException("Data must contain 'graph'.");
			return null;
		}
		List<Map<String, Object>> nodes = graph.get("nodes");
		List<Map<String, Object>> edges = graph.get("edges");
		if (nodes == null || edges == null) {
			throwException("Graph must contain 'nodes' and 'edges'.");
			return null;
		}

		for (Map<String, Object> node : nodes) {
			String label = node.get("label").toString();
			String nodeId = node.get("id").toString();
			Map<String, Object> properties = (Map<String, Object>) node.getOrDefault(
				"properties", Map.of("id", UUID.randomUUID()));
			String nodeVar = "n_" + nodeId;
			String propsVar = "p_" + nodeId;
			String prefix = topLevelNodes.contains(label) ? "(ns)-[:CONTAINS]->" : "";
			query.append(String.format("CREATE %s(%s:%s) SET %s = $%s\n",
				prefix, nodeVar, label, nodeVar, propsVar));
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
			Map<String, Object> properties = (Map<String, Object>) edge.getOrDefault(
				"properties", Map.of("id", UUID.randomUUID()));
			query.append(String.format("CREATE (%s)-[%s:%s]->(%s) SET %s = $%s\n",
				startVar, edgeVar, type, endVar, edgeVar, propsVar));
			params.put(propsVar, properties);
		}

		try {
			tx.execute(query.toString(), params);
		} catch (Exception e) {
			throwException("Error importing data.", e);
			return null;
		}
		return Map.of(
				"nsId", nsId,
				"objectIds", objectIds
		);
	}

	private Map<String, String> getObjectClassNames(Transaction tx, String nsId) {
		Map<String, String> nameLookup = new HashMap<>();
		String query = """
			MATCH (ns:TempNamespace {id: $nsId})-[:CONTAINS]->(obj:Object)-[:MEMBER_OF]->(cls:Class)
			RETURN obj.id AS objectId, cls.name AS className
		""";
		Result result = tx.execute(query, Map.of("nsId", nsId));
		while (result.hasNext()) {
			Map<String, Object> record = result.next();
			nameLookup.put(record.get("objectId").toString(), record.get("className").toString());
		}
		return nameLookup;
	}

	private Map<String, Object> getObjectData(Transaction tx, String nsId, String objectId) {
		String query = """
			MATCH (ns:TempNamespace {id: $nsId})-[:CONTAINS]->(obj:Object {id: $objectId})-[:MEMBER_OF]->(cls:Class)
			OPTIONAL MATCH (obj)-[:HAS_DESCRIPTOR]->(descr:Descriptor)
			WITH properties(obj) AS objProperties, cls.name AS className, COLLECT(DISTINCT properties(descr)) AS descriptors
			RETURN objProperties, className, descriptors
		""";
		Result result = tx.execute(query, Map.of("nsId", nsId, "objectId", objectId));
		if (result.hasNext()) {
			return result.next();
		} else {
			throwException("Could not find Object in TempNamespace: " + objectId);
			return null;
		}
	}

	private Map<String, Object> checkClass(Transaction tx, String nsId, String namespaceName, String className) {
		Map<String, Object> tempClassProperties;
		List<Map<String, Object>> tempClassDescriptors;
		List<Map<String, Object>> nsClassDescriptors = List.of();
		String query = """
			MATCH (ns:TempNamespace {id: $nsId})-[:CONTAINS]->(cls:Class {name: $className})
			OPTIONAL MATCH (cls)-[:HAS_DESCRIPTOR]->(descr:Descriptor)
			WITH properties(cls) AS clsProperties, COLLECT(DISTINCT properties(descr)) AS descriptors
			RETURN clsProperties, descriptors
		""";
		Result result = tx.execute(query, Map.of("nsId", nsId, "className", className));
		if (result.hasNext()) {
			Map<String, Object> row = result.next();
			tempClassProperties = (Map<String, Object>) row.get("clsProperties");
			tempClassDescriptors = (List<Map<String, Object>>) row.get("descriptors");
		} else {
			throwException("Could not find Class in TempNamespace: " + className);
			return null;
		}
		query = """
			MATCH (ns:Namespace {name: $namespaceName})-[:CONTAINS]->(cls:Class {name: $className})
			OPTIONAL MATCH (cls)-[:HAS_DESCRIPTOR]->(descr:Descriptor)
			WITH cls.name AS className, COLLECT(DISTINCT properties(descr)) AS descriptors
			RETURN className, descriptors
		""";
		result = tx.execute(query, Map.of("namespaceName", namespaceName, "className", className));
		String nsClassName = "";
		if (result.hasNext()) {
			Map<String, Object> row = result.next();
			nsClassName = row.get("className").toString();
			nsClassDescriptors = (List<Map<String, Object>>) row.get("descriptors");
		}
		if (!nsClassName.isEmpty()) {
			// Check if descriptors of the Namespace Class match those of the imported one.
			Map<String, String> existing = nsClassDescriptors.isEmpty() ? Map.of() : nsClassDescriptors.stream()
				.collect(Collectors.toMap(
					properties -> properties.get("name").toString(),
					properties -> properties.get("type").toString()
				));
			List<Map<String, Object>> toCreateDecriptors = new ArrayList<>();
			for (Map<String, Object> properties : tempClassDescriptors) {
				String name = properties.get("name").toString();
				String type = properties.get("type").toString();
				if (!existing.containsKey(name)) {
					toCreateDecriptors.add(properties);
				} else if (!existing.get(name).equals(type)) {
					throwException("Imported Descriptor of Class " + className + "has incorrect type: " + type + ". Expected: " + existing.get(name));
					return null;
				}
			}
			if (toCreateDecriptors.isEmpty()) {
				return null;
			} else {
				return Map.of(
					"properties", null,
					"descriptors", toCreateDecriptors
				);
			}
		} else {
			return Map.of(
				"properties", tempClassProperties,
				"descriptors", tempClassDescriptors
			);
		}
	}
}
