package deposit.repository;

import deposit.common.GraphSONData;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

import org.apache.commons.math3.util.CombinatoricsUtils;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ImportDataRepository extends BaseRepository {
	
	private static ImportDataRepository instance;
	private final GetObjectsByDataRepository getObjectsByDataRepository;
	private final GetObjectRepository getObjectRepository;
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
	private final CreateUserToolRepository createUserToolRepository;
		
	private ImportDataRepository() {
		this.getObjectsByDataRepository = GetObjectsByDataRepository.getInstance();
		this.getObjectRepository = GetObjectRepository.getInstance();
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
		this.createUserToolRepository = CreateUserToolRepository.getInstance();
	}
	
	public static synchronized ImportDataRepository getInstance() {
		if (instance == null) {
			instance = new ImportDataRepository();
		}
		return instance;
	}
	
	public void importData(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, 
			Map<String, Object> data, List<String> unique, List<String> overwrite, boolean matchEmpty) {

		Map<String, Map<String, ?>> parsed = GraphSONData.parseData(data);
		Map<String, GraphSONData.GraphsonObject> graphSONObjects = (Map<String, GraphSONData.GraphsonObject>) parsed.get("objects");
		Map<String, GraphSONData.GraphsonClass> graphSONClasses = (Map<String, GraphSONData.GraphsonClass>) parsed.get("classes");
		Map<String, GraphSONData.GraphsonUserTool> graphSONUserTools = (Map<String, GraphSONData.GraphsonUserTool>) parsed.get("userTools");
		Set<String> classesWithDuplicates = getExistingClassesWithDuplicates(tx, namespaceName, graphSONObjects);
		Set<String> toCreateObjects = new HashSet<>(); // Set of dataIds of Objects to create in the database
		Set<String> toOverwriteObjects = getObjectsToOverwrite(tx, namespaceName, graphSONObjects, overwrite); // Set of dataIds of Objects to overwrite in the database
		Map<String, String> objectIdMap = new HashMap<>(); // Matched Object ids for dataIds {dataId: Object id, ...}
		Set<String> allMatchedByDescriptors = new HashSet<>();
		Map<String, List<String>> createdByClass = new HashMap<>();
		List<List<GraphSONData.GraphsonObject>> subGraphs = GraphSONData.findSubgraphs(graphSONObjects);
		for (List<GraphSONData.GraphsonObject> subGraph : subGraphs) {
			processSubGraph(tx, namespaceName, graphSONObjects, subGraph, classesWithDuplicates, objectIdMap, 
					allMatchedByDescriptors, toCreateObjects, toOverwriteObjects, createdByClass, unique, overwrite, matchEmpty);
		}

		// Create Classes in the database
		createClasses(tx, timeStamp, username, namespaceName, versioningType, graphSONObjects, graphSONClasses, toCreateObjects);
		
		// Create Objects in the database
		createObjects(tx, timeStamp, username, namespaceName, versioningType, graphSONObjects, toCreateObjects, toOverwriteObjects, objectIdMap);

		// Create UserTools in the database
		createUserTools(tx, timeStamp, username, namespaceName, versioningType, graphSONUserTools);
	}
	
	private Set<String> getObjectsToOverwrite(Transaction tx, String namespaceName, Map<String, GraphSONData.GraphsonObject> graphSONObjects, List<String> overwrite) {
		Set<String> toOverwriteObjects = new HashSet<>();
		List<String> checkIds = new ArrayList<>();
		for (GraphSONData.GraphsonObject graphSONObject : graphSONObjects.values()) {
			if (overwrite.contains(graphSONObject.originalId)) {
				toOverwriteObjects.add(graphSONObject.id);
				checkIds.add(graphSONObject.originalId);
			}
		}
		if (checkIds.isEmpty()) {
			return toOverwriteObjects;
		}
		String query = """
			WITH $checkIds AS checkIds
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o:Object)
			WHERE o.id IN checkIds
			WITH collect(o.id) AS existingIds, checkIds
			RETURN size(existingIds) = size(checkIds) AS allExist
		""";
		Result result = tx.execute(query, Map.of(
			"namespaceName", namespaceName,
			"checkIds", checkIds
		));
		boolean allExist = false;
		if (result.hasNext()) {
			allExist = (boolean) result.next().get("allExist");
		}
		if (!allExist) {
			throwException("Some Object Ids specified in overwrite do not exist in the database.");
		}
		
		return toOverwriteObjects;
	}
	
	private void processSubGraph(Transaction tx, String namespaceName, Map<String, GraphSONData.GraphsonObject> graphSONObjects, 
			List<GraphSONData.GraphsonObject> subGraph, Set<String> classesWithDuplicates, Map<String, String> objectIdMap, 
			Set<String> allMatchedByDescriptors, Set<String> toCreateObjects, Set<String> toOverwriteObjects, 
			Map<String, List<String>> createdByClass, List<String> unique, List<String> overwrite, boolean matchEmpty) {
			
		// Try to match the whole subGraph first
		Map<String, List<String>> objectIdMapSubgraph = getObjectsByDataRepository.getObjectsByData(
				tx, namespaceName, subGraph, overwrite, matchEmpty);
		if (!objectIdMapSubgraph.isEmpty()) {
			if (objectIdMapSubgraph.values().stream().anyMatch(list -> list.size() > 1)) {
				throwException("Ambiguous match detected.");
			}
			objectIdMap.putAll(objectIdMapSubgraph.entrySet().stream()
					.collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get(0))));
			return;
		}
		
		// Find matching Objects in the database based on Class and Descriptors
		Set<String> unmatchedByDescriptors = new HashSet<>();
		List<String> matchedByDescriptors = new ArrayList<>();
		Set<String> allClasses = new HashSet<>();
		Map<String, GraphSONData.GraphsonObject> subGraphById = new HashMap<>();
		matchByDescriptors(tx, namespaceName, subGraph, subGraphById, unmatchedByDescriptors, matchedByDescriptors, allClasses, unique, overwrite);
		allMatchedByDescriptors.addAll(matchedByDescriptors);
		Set<String> subGraphClassesWithDuplicates = new HashSet<>(classesWithDuplicates);
		subGraphClassesWithDuplicates.removeIf(element -> !allClasses.contains(element));
		
		// Find a match for the largest possible subset of the subGraph in the database whilst minimizing number of
		//   Classes containing Objects with duplicate Descriptor values
		findLargestMatchingSubgraph(tx, namespaceName, graphSONObjects, subGraphById, classesWithDuplicates, objectIdMap, 
				allMatchedByDescriptors, matchedByDescriptors, unmatchedByDescriptors, allClasses, toCreateObjects,
				createdByClass, subGraphClassesWithDuplicates, overwrite, matchEmpty);
	}
	
	private void matchByDescriptors(Transaction tx, String namespaceName, List<GraphSONData.GraphsonObject> subGraph, 
			Map<String, GraphSONData.GraphsonObject> subGraphById, Set<String> unmatchedByDescriptors,
			List<String> matchedByDescriptors, Set<String> allClasses, List<String> unique, List<String> overwrite) {
		
		for (GraphSONData.GraphsonObject graphSONObject : subGraph) {
			subGraphById.put(graphSONObject.id, graphSONObject);
			allClasses.add(graphSONObject.className);
			if (overwrite.contains(graphSONObject.originalId)) {
				continue;
			}
			if (unique.contains(graphSONObject.className)) {
				unmatchedByDescriptors.add(graphSONObject.id);
				continue;
			}
			List<List<Object>> descriptors = new ArrayList<>();
			for (GraphSONData.Descriptor descriptor : graphSONObject.descriptors) {
				descriptors.add(List.of(descriptor.name, descriptor.value));
			}
			Stream<Node> objectsStream = getObjectRepository.getObject(tx, namespaceName, graphSONObject.className, descriptors);
			if (objectsStream.findAny().isEmpty()) {
				unmatchedByDescriptors.add(graphSONObject.id);
			} else {
				matchedByDescriptors.add(graphSONObject.id);
			}
		}		
	}
	
	private void findLargestMatchingSubgraph(Transaction tx, String namespaceName, 
			Map<String, GraphSONData.GraphsonObject> graphSONObjects, Map<String, GraphSONData.GraphsonObject> subGraphById, 
			Set<String> classesWithDuplicates, Map<String, String> objectIdMap, Set<String> allMatchedByDescriptors, 
			List<String> matchedByDescriptors, Set<String> unmatchedByDescriptors,	Set<String> allClasses,
			Set<String> toCreateObjects, Map<String, List<String>> createdByClass, 
			Set<String> subGraphClassesWithDuplicates, List<String> overwrite, boolean matchEmpty) {
		
		// Find a match for the largest possible subset of the subGraph in the database whilst minimizing number of Classes containing Objects with duplicate Descriptor values
		int nObjects = !unmatchedByDescriptors.isEmpty() ? 0 : 1; // Number of Objects matched by Descriptors to remove from the subGraph
		int nDuplicates = 0; // Acceptable number of extra Classes with duplicate values due to adding Objects
		Set<String> toCreateSubgraph = new HashSet<>();
		while (toCreateSubgraph.isEmpty()) {

			// Iterate over all combinations of nObjects from matchedByDescriptors
			Iterator<int[]> combinationsIterator = CombinatoricsUtils.combinationsIterator(matchedByDescriptors.size(), nObjects);
			while (combinationsIterator.hasNext()) {
				// Remove all Objects from unmatchedByDescriptors and selected Objects from matchedByDescriptors from the subGraph
				Set<String> removedIds = new HashSet<>(unmatchedByDescriptors);
				int[] combinationIndices = combinationsIterator.next();
				for (int index : combinationIndices) {
					String dataId = matchedByDescriptors.get(index);
					removedIds.add(dataId);
				}
				Map<String, GraphSONData.GraphsonObject> reducedSubGraph = new HashMap<>();
				for (String dataId : subGraphById.keySet()) {
					if (!removedIds.contains(dataId)) {
						reducedSubGraph.put(dataId, subGraphById.get(dataId));
					}
				}
				// Check if adding Objects to the database will add <= nDuplicates extra Classes with duplicate values
				if (getNewClassesWithDuplicates(
						graphSONObjects, removedIds, allMatchedByDescriptors, createdByClass, subGraphClassesWithDuplicates
					).size() > nDuplicates) {
					continue;
				}
				// Check if reduced subGraph exists in the database
				List<GraphSONData.GraphsonObject> reducedSubGraphList = new ArrayList<>(reducedSubGraph.values());
				Map<String, List<String>> objectIdMapSubgraph = getObjectsByDataRepository.getObjectsByData(
					tx, namespaceName, reducedSubGraphList, overwrite, matchEmpty);
				if (objectIdMapSubgraph.isEmpty()) {
					continue;
				}
				// Check if more than one match exists in the database
				if (!toCreateSubgraph.isEmpty() || objectIdMapSubgraph.values().stream().anyMatch(list -> list.size() > 1)) {
					throwException("Ambiguous match detected");
				}
				toCreateSubgraph.addAll(removedIds);
				objectIdMap.putAll(objectIdMapSubgraph.entrySet().stream()
						.collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get(0))));
			}
			nDuplicates++; // Increase acceptable number of extra Classes with duplicate values
			if (nDuplicates > allClasses.size() - subGraphClassesWithDuplicates.size()) {
				nDuplicates = 0;
				nObjects++; // Increase number of created Objects
			}
			if (nObjects > matchedByDescriptors.size()) {
				// Failsafe if no match found in the database
				toCreateSubgraph.addAll(subGraphById.keySet());
			}
		}
		Set<String> newClassesWithDuplicates = getNewClassesWithDuplicates(
			graphSONObjects, toCreateSubgraph, allMatchedByDescriptors, createdByClass, classesWithDuplicates);
		classesWithDuplicates.addAll(newClassesWithDuplicates);
		subGraphClassesWithDuplicates.addAll(newClassesWithDuplicates);
		toCreateObjects.addAll(toCreateSubgraph);
		for (String dataId : toCreateSubgraph) {
			createdByClass.computeIfAbsent(graphSONObjects.get(dataId).className, key -> new ArrayList<>()).add(dataId);
		}
	}
	
	private Set<String> getExistingClassesWithDuplicates(Transaction tx, String namespaceName,
														 Map<String, GraphSONData.GraphsonObject> graphSONObjects) {
		
		Set<String> usedClasses = graphSONObjects.values().stream()
			.map(graphSONObject -> graphSONObject.className)
			.collect(Collectors.toSet());
		Set<String> classesWithDuplicates = new HashSet<>();
		if (usedClasses.isEmpty()) {
			return classesWithDuplicates;
		}
		String query = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(class:Class)
			WHERE class.name IN $usedClasses
			MATCH (namespace)-[:CONTAINS]->(object:Object)-[:MEMBER_OF]->(class)
			WITH class, object
			MATCH (object)-[:HAS_DESCRIPTOR]->(descriptor:Descriptor)
			WITH class, object, COLLECT({name: descriptor.name, value: descriptor.value}) AS descriptorSet
			WITH class, COUNT(object) AS objectCount, COUNT(DISTINCT descriptorSet) AS descriptorSetCount
			WHERE objectCount > descriptorSetCount
			RETURN COLLECT(DISTINCT class.name) AS classNames
		""";
		Result result = tx.execute(query, Map.of(
			"namespaceName", namespaceName,
			"usedClasses", usedClasses
		));
		if (result.hasNext()) {
			Map<String, Object> record = result.next();
			classesWithDuplicates.addAll((List<String>) record.get("classNames"));
		}
		return classesWithDuplicates;
	}
	
	private Set<String> getNewClassesWithDuplicates(Map<String, GraphSONData.GraphsonObject> graphSONObjects,
			Set<String> newDataIds, Set<String> matchedDataIds, Map<String, List<String>> createdByClass, 
			Set<String> classesWithDuplicates) {
		
		// Count number of new Classes with duplicate values if Objects from newDataIds are added to the database
		Set<String> newDuplicates = new HashSet<>();
		for (String dataId : newDataIds) {
			String className = graphSONObjects.get(dataId).className;
			if (!classesWithDuplicates.contains(className)) {
				if (matchedDataIds.contains(dataId) || duplicateCreatedInClass(graphSONObjects, dataId, className, createdByClass)) {
					// New duplicate created
					newDuplicates.add(className);
				}
			}
		}
		return newDuplicates;
	}
	
	private boolean duplicateCreatedInClass(Map<String, GraphSONData.GraphsonObject> graphSONObjects, String dataId, 
			String className, Map<String, List<String>> createdByClass) {
		
		if (!createdByClass.containsKey(className)) {
			return false;
		}
		List<GraphSONData.Descriptor> descriptors = graphSONObjects.get(dataId).descriptors;
		for (String otherId : createdByClass.get(className)) {
			if (graphSONObjects.get(otherId).descriptors.equals(descriptors)) {
				return true;
			}
		}
		return false;
	}
	
	private void createClasses(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName,
			String versioningType, Map<String, GraphSONData.GraphsonObject> graphSONObjects,
			Map<String, GraphSONData.GraphsonClass> graphSONClasses, Set<String> toCreateObjects) {

		// Find the highest Class order
		List<Node> classNodes = getClassRepository.getClass(tx, namespaceName, "").collect(Collectors.toList());
		Set<String> existingClasses = new HashSet<>();
		Long classOrder = 0L;
		for (Node node : classNodes) {
			Long order = (Long) node.getProperty("order", 0L);
			classOrder = order > classOrder ? order : classOrder;
			existingClasses.add((String) node.getProperty("name"));
		}
		classOrder++;
		
		// Collect Classes & Class Descriptors to create
		Map<String, Set<String>> toCreateClasses = new HashMap<>(); // {className: [descriptorName, ...], ...}
		for (String dataId : toCreateObjects) {
			GraphSONData.GraphsonObject graphSONObject = graphSONObjects.get(dataId);
			String className = graphSONObject.className;
			if (!existingClasses.contains(className) && !toCreateClasses.containsKey(className)) {
				toCreateClasses.put(className, graphSONObject.descriptors.stream()
						.map(descriptor -> descriptor.name)
						.collect(Collectors.toSet())
						);
			}
		}

		Map<String, GraphSONData.GraphsonClass> graphSONClassesByName = new HashMap<>();
		for (GraphSONData.GraphsonClass graphsonClass : graphSONClasses.values()) {
			graphSONClassesByName.put(graphsonClass.name, graphsonClass);
		}

		for (Map.Entry<String, Set<String>> entry : toCreateClasses.entrySet()) {
			String className = entry.getKey();
			Set<String> descriptors = entry.getValue();
			
			createClass(tx, timeStamp, username, namespaceName, versioningType, 
					graphSONClassesByName, className, descriptors, classOrder);
			
			classOrder++;
		}
		
		// Create Class Relationships
		createClassRelationships(tx, timeStamp, username, namespaceName, versioningType, graphSONClasses);
	}
	
	private void createClass(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName,
			String versioningType, Map<String, GraphSONData.GraphsonClass> graphSONClassesByName, String className, Set<String> descriptors, Long classOrder) {
		
		// Find highest Class Descriptor order
		Long descrOrder = 0L;
		Set<String> toCreateDescriptors = new HashSet<>(descriptors);
		if (!descriptors.isEmpty()) {
			List<Node> descriptorNodes = getClassDescriptorRepository.getClassDescriptor(tx, namespaceName, className, "").collect(Collectors.toList());
			for (Node node : descriptorNodes) {
				Long order = (Long) node.getProperty("order", 0L);
				descrOrder = order > descrOrder ? order : descrOrder;
				toCreateDescriptors.remove((String) node.getProperty("name"));
			}
		}
		descrOrder++;
		
		// Collect Class data and create it
		List<Double> position = List.of();
		String comment = "";
		Map<String, GraphSONData.ClassDescriptor> classDescriptors = new HashMap<>();
		if (graphSONClassesByName.containsKey(className)) {
			GraphSONData.GraphsonClass graphSONClass = graphSONClassesByName.get(className);
			position = graphSONClass.position;
			comment = graphSONClass.comment;
			for (GraphSONData.ClassDescriptor classDescriptor : graphSONClass.descriptors) {
				classDescriptors.put(classDescriptor.name, classDescriptor);
			}
		}
		createClassRepository.createClass(tx, timeStamp, username, namespaceName, versioningType, className, classOrder, position, comment);
		
		// Create Class Descriptors
		for (String descriptorName: toCreateDescriptors) {
			String descriptorType = "String";
			String descriptorComment = "";
			if (classDescriptors.containsKey(descriptorName)) {
				descriptorType = classDescriptors.get(descriptorName).type;
				descriptorComment = classDescriptors.get(descriptorName).comment;
			}
			createClassDescriptorRepository.createClassDescriptor(tx, timeStamp, username, namespaceName, versioningType, className, descriptorName, descriptorType, descrOrder, descriptorComment);
			descrOrder++;
		}		
	}
	
	private void createClassRelationships(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName,
			String versioningType, Map<String, GraphSONData.GraphsonClass> graphSONClasses) {
		
		// Check which Class Relationships don't exist in the database and create them
		List<Map<String, String>> relationshipInfoList = new ArrayList<>();
		for (GraphSONData.GraphsonClass graphsonClass : graphSONClasses.values()) {
			for (GraphSONData.ClassRelationship relationship : graphsonClass.relationships) {
				Map<String, String> relationshipInfo = new HashMap<>();
				relationshipInfo.put("source", graphsonClass.name);
				relationshipInfo.put("target", graphSONClasses.get(relationship.targetId).name);
				relationshipInfo.put("label", relationship.label);
				relationshipInfo.put("comment", relationship.comment);
				relationshipInfoList.add(relationshipInfo);
			}
		}
		String query = """
			MATCH (ns:Namespace {name: $namespaceName})
			WITH ns, $relationshipInfoList as relationshipInfoList
			UNWIND relationshipInfoList AS relationshipInfo
			WITH ns, relationshipInfo
			WHERE NOT EXISTS {
				MATCH (ns)-[:CONTAINS]->(:Class {name: relationshipInfo.source})-[:RELATED_TO {label: relationshipInfo.label}]->(:Class {name: relationshipInfo.target})
			}
			RETURN relationshipInfo.source AS source, relationshipInfo.target AS target, relationshipInfo.label AS label, relationshipInfo.comment AS comment
		""";
		try {
			Result result = tx.execute(query, Map.of(
					"namespaceName", namespaceName,
					"relationshipInfoList", relationshipInfoList
			));
			while (result.hasNext()) {
				Map<String, Object> row = result.next();
				String source = row.get("source").toString();
				String target = row.get("target").toString();
				String label = row.get("label").toString();
				String comment = row.get("comment").toString();
				createClassRelationshipRepository.createClassRelationship(tx, timeStamp, username, namespaceName, versioningType, source, target, label, comment);
			}
		} catch (Exception e) {
			throwException("Error creating Class Relationships", e);
		}
	}
	
	private void createObjects(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName,
			String versioningType, Map<String, GraphSONData.GraphsonObject> graphSONObjects, Set<String> toCreateObjects,
			Set<String> toOverwriteObjects, Map<String, String> objectIdMap) {
		
		// Create Objects
		for (String dataId : toCreateObjects) {
			GraphSONData.GraphsonObject graphSONObject = graphSONObjects.get(dataId);
			Node node = createObjectRepository.createObject(tx, timeStamp, username, namespaceName, versioningType, graphSONObject.position);
			String objectId = node.getProperty("id").toString();
			setMembershipRepository.setMembership(tx, timeStamp, username, namespaceName, versioningType, graphSONObject.className, objectId);
			objectIdMap.put(dataId, objectId);
		}
		
		// Delete existing Descriptors for Objects in the overwrite list
		for (String dataId : toOverwriteObjects) {
			String objectId = objectIdMap.get(dataId);
			deleteObjectDescriptors(tx, timeStamp, username, namespaceName, versioningType, objectId);
		}
		
		// Create or update Descriptors for each imported Object
		for (Map.Entry<String, String> entry : objectIdMap.entrySet()) {
			for (GraphSONData.Descriptor descriptor : graphSONObjects.get(entry.getKey()).descriptors) {
				createDescriptorRepository.createDescriptor(tx, timeStamp, username, namespaceName, versioningType, entry.getValue(), descriptor.name, descriptor.value, true);
			}
		}
		
		createObjectRelationships(tx, timeStamp, username, namespaceName, versioningType, graphSONObjects, objectIdMap);
	}
	
	private void deleteObjectDescriptors(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName,
			String versioningType, String objectId) {
		
		String query = """
			MATCH (ns:Namespace {name: $namespaceName})-[:CONTAINS]->(obj:Object {id: $objectId})
			OPTIONAL MATCH (obj)-[:HAS_DESCRIPTOR]->(descr:Descriptor)
			WITH COLLECT(DISTINCT descr.id) AS descriptorIds
			RETURN descriptorIds
		""";
		List<String> descriptorIds = new ArrayList<>();
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
	}
	
	private void createObjectRelationships(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName,
			String versioningType, Map<String, GraphSONData.GraphsonObject> graphSONObjects, Map<String, String> objectIdMap) {
		
		// Check which Relationships don't exist in the database and create them
		List<Map<String, String>> relationshipInfoList = new ArrayList<>();
		for (GraphSONData.GraphsonObject graphsonObject : graphSONObjects.values()) {
			for (GraphSONData.Relationship relationship : graphsonObject.relationships) {
				Map<String, String> relationshipInfo = new HashMap<>();
				relationshipInfo.put("source", objectIdMap.get(graphsonObject.id));
				relationshipInfo.put("target", objectIdMap.get(graphSONObjects.get(relationship.targetId).id));
				relationshipInfo.put("label", relationship.label);
				relationshipInfo.put("weight", relationship.weight.toString());
				relationshipInfoList.add(relationshipInfo);
			}
		}
		String query = """
			MATCH (ns:Namespace {name: $namespaceName})
			WITH ns, $relationshipInfoList as relationshipInfoList
			UNWIND relationshipInfoList AS relationshipInfo
			WITH ns, relationshipInfo
			WHERE NOT EXISTS {
				MATCH (ns)-[:CONTAINS]->(:Object {id: relationshipInfo.source})-[:RELATED_TO {label: relationshipInfo.label}]->(:Object {id: relationshipInfo.target})
			}
			RETURN relationshipInfo.source AS source, relationshipInfo.target AS target, relationshipInfo.label AS label, relationshipInfo.weight AS weight
		""";
		try {
			Result result = tx.execute(query, Map.of(
					"namespaceName", namespaceName,
					"relationshipInfoList", relationshipInfoList
			));
			while (result.hasNext()) {
				Map<String, Object> row = result.next();
				String source = row.get("source").toString();
				String target = row.get("target").toString();
				String label = row.get("label").toString();
				Double weight = Double.valueOf(row.get("weight").toString());
				createRelationshipRepository.createRelationship(tx, timeStamp, username, namespaceName, versioningType, source, target, label, weight);
			}
		} catch (Exception e) {
			throwException("Error creating Relationships", e);
		}
	}
	
	private void createUserTools(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName,
			String versioningType, Map<String, GraphSONData.GraphsonUserTool> graphSONUserTools) {

		// Check which User Tools don't exist in the database and create them
		List<Map<String, String>> userToolInfoList = new ArrayList<>();
		for (GraphSONData.GraphsonUserTool graphsonUserTool : graphSONUserTools.values()) {
			Map<String, String> userToolInfo = new HashMap<>();
			userToolInfo.put("name", graphsonUserTool.name);
			userToolInfo.put("data", graphsonUserTool.data);
			userToolInfo.put("comment", graphsonUserTool.comment);
			userToolInfoList.add(userToolInfo);
		}
		String query = """
			MATCH (ns:Namespace {name: $namespaceName})
			WITH ns, $userToolInfoList as userToolInfoList
			UNWIND userToolInfoList AS userToolInfo
			WITH ns, userToolInfo
			WHERE NOT EXISTS {
				MATCH (ns)-[:CONTAINS]->(:UserTool {name: userToolInfo.name})
			}
			RETURN userToolInfo.name AS name, userToolInfo.data AS data, userToolInfo.comment AS comment
		""";
		try {
			Result result = tx.execute(query, Map.of(
					"namespaceName", namespaceName,
					"userToolInfoList", userToolInfoList
			));
			while (result.hasNext()) {
				Map<String, Object> row = result.next();
				String name = row.get("name").toString();
				String data = row.get("data").toString();
				String comment = row.get("comment").toString();
				createUserToolRepository.createUserTool(tx, timeStamp, username, namespaceName, versioningType, name, data, comment);
			}
		} catch (Exception e) {
			throwException("Error creating User Tools", e);
		}		
	}
}
