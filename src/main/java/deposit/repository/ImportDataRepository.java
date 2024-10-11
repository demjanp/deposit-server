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
		Set<String> toOverwriteObjects = getObjectsToOverwrite(graphSONObjects, overwrite); // Set of dataIds of Objects to overwrite in the database
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
	
	private Set<String> getObjectsToOverwrite(Map<String, GraphSONData.GraphsonObject> graphSONObjects, List<String> overwrite) {
		// TODO check if originalIds exist in the database and if not, throw an exception
		Set<String> toOverwriteObjects = new HashSet<>();
		for (GraphSONData.GraphsonObject graphSONObject : graphSONObjects.values()) {
			if (overwrite.contains(graphSONObject.originalId)) {
				toOverwriteObjects.add(graphSONObject.id);
			}
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
				throwException("Ambiguous match detected");
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

		// Find highest Class order
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
			
			// Find highest Class Descriptor order
			Long descrOrder = 1L;
			Set<String> toCreateDescriptors = new HashSet<>(descriptors);
			if (!descriptors.isEmpty()) {
				List<Node> descriptorNodes = getClassDescriptorRepository.getClassDescriptor(tx, namespaceName, className, "").collect(Collectors.toList());
				for (Node node : descriptorNodes) {
					Long order = (Long) node.getProperty("order", 0L);
					descrOrder = order > descrOrder ? order : descrOrder;
					toCreateDescriptors.remove((String) node.getProperty("name"));
				}
				descrOrder++;
			}

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

			// TODO create Class (including order, position, comment)
			System.out.println("\nCREATE CLASS: " + graphSONClassesByName.get(className)); // DEBUG

			for (String descriptorName: toCreateDescriptors) {
				String descriptorType = "String";
				String descriptorComment = "";
				if (classDescriptors.containsKey(descriptorName)) {
					descriptorType = classDescriptors.get(descriptorName).type;
					descriptorComment = classDescriptors.get(descriptorName).comment;
				}

				// TODO create Class Descriptor (including type, order, comment)
				System.out.println("\tCREATE CLS DESCR: " + classDescriptors.get(descriptorName)); // DEBUG

				descrOrder++;
			}
			classOrder++;
		}
		
		// TODO create Class Relationships (for all graphSONClasses where they don't exist, not just toCreateClasses)
	}
	
	private void createObjects(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName,
			String versioningType, Map<String, GraphSONData.GraphsonObject> graphSONObjects, Set<String> toCreateObjects,
			Set<String> toOverwriteObjects, Map<String, String> objectIdMap) {

		for (String dataId : toCreateObjects) {
			GraphSONData.GraphsonObject graphSONObject = graphSONObjects.get(dataId);
			String className = graphSONObject.className;
			// TODO create Object (including position, add id to objectIdMap)
			// TODO create Descriptors
			System.out.println("CREATE OBJECT: " + graphSONObject);
		}
		// TODO delete Descriptors and create new ones for Objects from the toOverwriteObjects list
		
		// TODO create Relationships (for all graphSONObjects where they don't exist, not just toCreateObjects)
	}

	private void createUserTools(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName,
			String versioningType, Map<String, GraphSONData.GraphsonUserTool> graphSONUserTools) {

		// TODO create UserTools which do not already exist in the database
	}
}
