package deposit.repository;

import deposit.service.data.DataHandler;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

public class ImportDataRepository extends BaseRepository {

	private static ImportDataRepository instance;
	private final GetDataRepository getDataRepository;
	private final GetObjectsByDataRepository getObjectsByDataRepository;
	private final GetClassRepository getClassRepository;
	private final CreateClassRepository createClassRepository;
	private final GetClassDescriptorRepository getClassDescriptorRepository;
	private final CreateClassDescriptorRepository createClassDescriptorRepository;
	private final CreateObjectRepository createObjectRepository;
	private final SetMembershipRepository setMembershipRepository;
	private final DeleteDescriptorByIdRepository deleteDescriptorByIdRepository;
	private final CreateDescriptorRepository createDescriptorRepository;
	private final CreateRelationshipRepository createRelationshipRepository;
	private final CreateClassRelationshipRepository createClassRelationshipRepository;
	
	private ImportDataRepository() {
		this.getDataRepository = GetDataRepository.getInstance();
		this.getObjectsByDataRepository = GetObjectsByDataRepository.getInstance();
		this.getClassRepository = GetClassRepository.getInstance();
		this.createClassRepository = CreateClassRepository.getInstance();
		this.getClassDescriptorRepository = GetClassDescriptorRepository.getInstance();
		this.createClassDescriptorRepository = CreateClassDescriptorRepository.getInstance();
		this.createObjectRepository = CreateObjectRepository.getInstance();
		this.setMembershipRepository = SetMembershipRepository.getInstance();
		this.deleteDescriptorByIdRepository = DeleteDescriptorByIdRepository.getInstance();
		this.createDescriptorRepository = CreateDescriptorRepository.getInstance();
		this.createRelationshipRepository = CreateRelationshipRepository.getInstance();
		this.createClassRelationshipRepository = CreateClassRelationshipRepository.getInstance();
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

		// Collect data on Objects and Classes to create
		Map<String, String> objectIdMap = new HashMap<>(); // maps Object ids in data to ids in the active Namespace
		Map<String, Object> toCreateObjects = new HashMap<>();
		Set<String> classNames = new HashSet<>();
		collectData(tx, namespaceName, unique, overwrite, matchEmpty, nsId,  objectIds, objectIdMap, toCreateObjects, classNames);

		// Check if collected Classes & their Descriptors don't collide with those in the active Namespace
		// return Class data if Class needs to be created
		Map<String, Object> toCreateClasses = new HashMap<>();
		for (String className : classNames) {
			Map<String, Object> classData = checkClass(tx, nsId, namespaceName, className);
			if (classData != null) {
				toCreateClasses.put(className, classData);
			}
		}

		// Create Classes and their Descriptors
		if (!toCreateClasses.isEmpty()) {
			createClasses(tx, timeStamp, username, namespaceName, versioningType, toCreateClasses);
		}

		if (!toCreateObjects.isEmpty()) {
			createObjects(tx, timeStamp, username, namespaceName, versioningType, overwrite, toCreateObjects, objectIdMap);
		}

		// Re-create all Relationships between Objects from TempNamespace in the active Namespace
		creteObjectRelationships(tx, timeStamp, username, namespaceName, versioningType, nsId, objectIdMap);

		// Re-create all Relationships between Classes from TempNamespace in the active Namespace
		creteClassRelationships(tx, timeStamp, username, namespaceName, versioningType, nsId);

		// Delete the TempNamespace subtree
		deleteTempNamespace(tx, nsId);
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

	private void collectData(Transaction tx, String namespaceName, List<String> unique, List<String> overwrite,
							 boolean matchEmpty, String nsId, Set<String> objectIds, Map<String, String> objectIdMap,
							 Map<String, Object> toCreateObjects, Set<String> classNames) {
		// Get Class names of imported Objects
		Map<String, String> objectClassNames = getObjectClassNames(tx, nsId);

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
	}

	private Map<String, String> getObjectClassNames(Transaction tx, String nsId) {
		Map<String, String> nameLookup = new HashMap<>();
		String query = """
			MATCH (ns:TempNamespace {id: $nsId})-[:CONTAINS]->(obj:Object)-[:MEMBER_OF]->(cls:Class)
			RETURN obj.id AS objectId, cls.name AS className
		""";
		try {
			Result result = tx.execute(query, Map.of("nsId", nsId));
			while (result.hasNext()) {
				Map<String, Object> record = result.next();
				nameLookup.put(record.get("objectId").toString(), record.get("className").toString());
			}
			return nameLookup;
		} catch (Exception e) {
			throwException("Error retrieving Class names for Objects", e);
		}
		return null;
	}

	private Map<String, Object> getObjectData(Transaction tx, String nsId, String objectId) {
		String query = """
			MATCH (ns:TempNamespace {id: $nsId})-[:CONTAINS]->(obj:Object {id: $objectId})-[:MEMBER_OF]->(cls:Class)
			OPTIONAL MATCH (obj)-[:HAS_DESCRIPTOR]->(descr:Descriptor)
			WITH properties(obj) AS objProperties, cls.name AS className, COLLECT(DISTINCT properties(descr)) AS descriptors
			RETURN objProperties, className, descriptors
		""";
		try {
			Result result = tx.execute(query, Map.of("nsId", nsId, "objectId", objectId));
			if (result.hasNext()) {
				return result.next();
			} else {
				throwException("Could not find Object in TempNamespace: " + objectId);
				return null;
			}
		} catch (Exception e) {
			throwException("Error retrieving data for Object " + objectId, e);
		}
		return null;
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
		try {
			Result result = tx.execute(query, Map.of("nsId", nsId, "className", className));
			if (result.hasNext()) {
				Map<String, Object> row = result.next();
				tempClassProperties = (Map<String, Object>) row.get("clsProperties");
				tempClassDescriptors = (List<Map<String, Object>>) row.get("descriptors");
			} else {
				throwException("Could not find Class in TempNamespace: " + className);
				return null;
			}
		} catch (Exception e) {
			throwException("Error matching Class in TempNamespace", e);
			return null;
		}
		query = """
			MATCH (ns:Namespace {name: $namespaceName})-[:CONTAINS]->(cls:Class {name: $className})
			OPTIONAL MATCH (cls)-[:HAS_DESCRIPTOR]->(descr:Descriptor)
			WITH cls.name AS className, COLLECT(DISTINCT properties(descr)) AS descriptors
			RETURN className, descriptors
		""";
		String nsClassName = "";
		try {
			Result result = tx.execute(query, Map.of("namespaceName", namespaceName, "className", className));
			if (result.hasNext()) {
				Map<String, Object> row = result.next();
				nsClassName = row.get("className").toString();
				nsClassDescriptors = (List<Map<String, Object>>) row.get("descriptors");
			}
		} catch (Exception e) {
			throwException("Error matching Class in active Namespace", e);
			return null;
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

	private void createClasses(Transaction tx, ZonedDateTime timeStamp, String username,
		String namespaceName, String versioningType, Map<String, Object> toCreateClasses) {

		// Find highest Class order
		List<Node> classNodes = getClassRepository.getClass(tx, namespaceName, "").collect(Collectors.toList());
		Long classOrder = classNodes.stream()
			.map(node -> (Long) node.getProperty("order", 0L))
			.max(Long::compare)
			.orElse(0L);
		classOrder++;
		for (Map.Entry<String, Object> entry : toCreateClasses.entrySet()) {
			String className = entry.getKey();
			Map<String, Object> entryValue = (Map<String, Object>) entry.getValue();
			Map<String, Object> properties = (Map<String, Object>) entryValue.get("properties");
			List<Map<String, Object>> descriptors = (List<Map<String, Object>>) entryValue.get("descriptors");
			Long descrOrder = 1L;
			if (properties != null) {

				// Create Class
				List<Double> position = (List<Double>) properties.get("position");
				String comment = properties.get("comment").toString();
				createClassRepository.createClass(tx, timeStamp, username, namespaceName, versioningType, className, classOrder, position, comment);
				classOrder++;
			} else if (!descriptors.isEmpty()) {

				// Find highest Class Descriptor order
				List<Node> descriptorNodes = getClassDescriptorRepository.getClassDescriptor(tx, namespaceName, className, "").collect(Collectors.toList());
				descrOrder = descriptorNodes.stream()
					.map(node -> (Long) node.getProperty("order", 0L))
					.max(Long::compare)
					.orElse(0L);
				descrOrder++;
			}

			// Create Class Descriptors
			for (Map<String, Object> descrProperties : descriptors) {
				String descriptorName = descrProperties.get("name").toString();
				String type = descrProperties.get("type").toString();
				String comment = descrProperties.get("comment").toString();
				createClassDescriptorRepository.createClassDescriptor(tx, timeStamp, username, namespaceName, versioningType, className, descriptorName, type, descrOrder, comment);
				descrOrder++;
			}
		}
	}

	private void createObjects(Transaction tx, ZonedDateTime timeStamp, String username,
		String namespaceName, String versioningType, List<String> overwrite, Map<String, Object> toCreateObjects, Map<String, String> objectIdMap) {
		for (Map.Entry<String, Object> entry : toCreateObjects.entrySet()) {
			String tempObjectId = entry.getKey();
			Map<String, Object> entryValue = (Map<String, Object>) entry.getValue();
			String className = entryValue.get("className").toString();
			Map<String, Object> properties = (Map<String, Object>) entryValue.get("properties");
			List<Map<String, Object>> descriptors = (List<Map<String, Object>>) entryValue.get("descriptors");
			String objectId;
			if (overwrite.contains(tempObjectId)) {

				// Keep object and overwrite Descriptors
				objectId = tempObjectId;
				String query = """
					MATCH (ns:Namespace {name: $namespaceName})-[:CONTAINS]->(obj:Object {id: $objectId})
					OPTIONAL MATCH (obj)-[:HAS_DESCRIPTOR]->(descr:Descriptor)
					WITH COLLECT(DISTINCT descr.id) AS descriptorIds
					RETURN descriptorIds
				""";
				List<String> descriptorIds = List.of();
				try {
					Result result = tx.execute(query, Map.of("namespaceName", namespaceName, "objectId", objectId));
					if (result.hasNext()) {
						Map<String, Object> row = result.next();
						descriptorIds = (List<String>) row.get("descriptorIds");
					}
				} catch (Exception e) {
					throwException("Error finding Descriptors in TempNamespace", e);
				}
				for (String descriptorId : descriptorIds) {
					deleteDescriptorByIdRepository.deleteDescriptorById(tx, timeStamp, username, namespaceName, versioningType, descriptorId);
				}

			} else {

				// Create Object
				List<Double> position = (List<Double>) properties.get("position");
				Node node = createObjectRepository.createObject(tx, timeStamp, username, namespaceName, versioningType, position);
				objectId = node.getProperty("id").toString();
				setMembershipRepository.setMembership(tx, timeStamp, username, namespaceName, versioningType, className, objectId);
			}
			objectIdMap.put(tempObjectId, objectId);

			// Create Descriptors
			for (Map<String, Object> descrProperties : descriptors) {
				String descriptorName = descrProperties.get("name").toString();
				Object value = descrProperties.get("value");
				createDescriptorRepository.createDescriptor(tx, timeStamp, username, namespaceName, versioningType, objectId, descriptorName, value, false);
			}
		}
	}

	private void creteObjectRelationships(Transaction tx, ZonedDateTime timeStamp, String username,
		String namespaceName, String versioningType, String nsId, Map<String, String> objectIdMap) {
		String query = """
			MATCH (:TempNamespace {id: $nsId})-[:CONTAINS]->(startObj:Object)-[rel:RELATED_TO]->(endObj:Object)
			WHERE NOT EXISTS { MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object {id: $objectIdMap[startObj.id]})-[:RELATED_TO {label: rel.label}]->(:Object {id: $objectIdMap[endObj.id]}) }
			RETURN startObj.id AS startObjectId, endObj.id AS endObjectId, rel.label AS label, rel.weight AS weight
		""";
		try {
			Result result = tx.execute(query, Map.of(
					"nsId", nsId,
					"namespaceName", namespaceName,
					"objectIdMap", objectIdMap
			));
			while (result.hasNext()) {
				Map<String, Object> row = result.next();
				String startObjectId = objectIdMap.get(row.get("startObjectId").toString());
				String endObjectId = objectIdMap.get(row.get("endObjectId").toString());
				String label = row.get("label").toString();
				Double weight = (Double) row.get("weight");
				createRelationshipRepository.createRelationship(tx, timeStamp, username, namespaceName, versioningType, startObjectId, endObjectId, label, weight);
			}
		} catch (Exception e) {
			throwException("Error creating Object Relationships", e);
		}
	}

	private void creteClassRelationships(Transaction tx, ZonedDateTime timeStamp, String username,
		String namespaceName, String versioningType, String nsId) {
		String query = """
			MATCH (:TempNamespace {id: $nsId})-[:CONTAINS]->(startCls:Class)-[rel:RELATED_TO]->(endCls:Class)
			WHERE NOT EXISTS { MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class {name: startCls.name})-[:RELATED_TO {label: rel.label}]->(:Class {name: endCls.name}) }
			RETURN startCls.name AS startClassName, endCls.name AS endClassName, rel.label AS label, rel.comment AS comment
		""";
		try {
			Result result = tx.execute(query, Map.of(
					"nsId", nsId,
					"namespaceName", namespaceName
			));
			while (result.hasNext()) {
				Map<String, Object> row = result.next();
				String startClassName = row.get("startClassName").toString();
				String endClassName = row.get("endClassName").toString();
				String label = row.get("label").toString();
				String comment = row.get("comment").toString();
				createClassRelationshipRepository.createClassRelationship(tx, timeStamp, username, namespaceName, versioningType, startClassName, endClassName, label, comment);
			}
		} catch (Exception e) {
			throwException("Error creating Class Relationships", e);
		}
	}

	private void deleteTempNamespace(Transaction tx, String nsId) {
		String query = """
			MATCH (namespace:TempNamespace {id: $nsId})-[r*0..]->(n)
			DETACH DELETE namespace, n
		""";
		try {
			tx.execute(query, Map.of("nsId", nsId));
		} catch (Exception e) {
			throwException("Error deleting TempNamespace", e);
		}
	}
}
