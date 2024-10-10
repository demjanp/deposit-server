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

	private ImportDataRepository() {
		this.getObjectsByDataRepository = GetObjectsByDataRepository.getInstance();
		this.getObjectRepository = GetObjectRepository.getInstance();
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

		Map<String, GraphSONData.GraphsonObject> graphSONObjects = GraphSONData.parseData(data);

		System.out.println(); // DEBUG
		System.out.println("---- IMPORTING ----"); // DEBUG
		System.out.println(); // DEBUG
		System.out.println(graphSONObjects); // DEBUG
		System.out.println(); // DEBUG
		Set<String> toCreate = new HashSet<>(); // Set of dataIds of Objects to create in the database
		Map<String, String> objectIdMap = new HashMap<>(); // Matched Object ids for dataIds {dataId: Object id, ...}
		Map<String, List<String>> createdByClass = new HashMap<>();
		Set<String> allMatchedByDescriptors = new HashSet<>();
		List<List<GraphSONData.GraphsonObject>> subGraphs = GraphSONData.findSubgraphs(graphSONObjects);
		for (List<GraphSONData.GraphsonObject> subGraph : subGraphs) {
			System.out.println("--- NEW SUBGRAPH ---"); // DEBUG
			if (getObjectsByDataRepository.getObjectsByData(tx, namespaceName, subGraph, matchEmpty).isEmpty()) {

				// Find Objects with existing matches in the database based on Class and Descriptors
				List<String> unmatchedByDescriptors = new ArrayList<>();
				List<String> matchedByDescriptors = new ArrayList<>();
				Set<String> allClasses = new HashSet<>();
				Map<String, GraphSONData.GraphsonObject> subGraphById = new HashMap<>();
				for (GraphSONData.GraphsonObject graphSONObject : subGraph) {
					allClasses.add(graphSONObject.className);
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
					subGraphById.put(graphSONObject.id, graphSONObject);
				}
				allMatchedByDescriptors.addAll(matchedByDescriptors);

				// Collect Classes from graphSONObjects which contain duplicate values in the database
				Set<String> classesWithDuplicates = getClassesWithDuplicates(tx, namespaceName, allClasses);

				// Find a match for the largest possible subset of the subGraph in the database whilst minimizing number of Classes containing Objects with duplicate Descriptor values
				int nObjects = !unmatchedByDescriptors.isEmpty() ? 0 : 1; // Number of Objects matched by Descriptors to remove from the subGraph
				int nDuplicates = 0; // Acceptable number of extra Classes with duplicate values due to adding Objects
				Set<String> toCreateSubgraph = new HashSet<>();
				while (toCreateSubgraph.isEmpty()) {

					// Iterate over all combinations of nObjects from matchedByDescriptors
					Iterator<int[]> combinationsIterator = CombinatoricsUtils.combinationsIterator(matchedByDescriptors.size(), nObjects);
					while (combinationsIterator.hasNext()) {
						int[] combinationIndices = combinationsIterator.next();

						// Remove all Objects from unmatchedByDescriptors and selected Objects from matchedByDescriptors from the subGraph
						Set<String> removedIds = new HashSet<>(unmatchedByDescriptors);
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
						System.out.println("TRY: " + removedIds.size() + " / " + subGraph.size() + "; nObjects: " + nObjects + ", nDuplicates: " + nDuplicates); // DEBUG

						// CHECK A // DEBUG
						// Check if adding Objects to the database will add <= nDuplicates extra Classes with duplicate values
						System.out.println("NEW DUPES: " + getNewClassesWithDuplicates(removedIds, allMatchedByDescriptors, createdByClass, classesWithDuplicates, graphSONObjects).size()); // DEBUG
						if (getNewClassesWithDuplicates(removedIds, allMatchedByDescriptors, createdByClass, classesWithDuplicates, graphSONObjects).size() <= nDuplicates) {

							// CHECK B // DEBUG
							// Check if reduced subGraph exists in the database
							System.out.println(); // DEBUG
							System.out.println("---- MATCHING ----"); // DEBUG
							System.out.println("Removed:" + removedIds); // DEBUG
							System.out.println(); // DEBUG
							System.out.println(reducedSubGraph); // DEBUG
							System.out.println(); // DEBUG
							List<GraphSONData.GraphsonObject> reducedSubGraphList = new ArrayList<>(reducedSubGraph.values());
							Map<String, List<String>> objectIdMapSubgraph = getObjectsByDataRepository.getObjectsByData(tx, namespaceName, reducedSubGraphList, matchEmpty);
							if (!objectIdMapSubgraph.isEmpty()) {
								if (!toCreateSubgraph.isEmpty() || objectIdMapSubgraph.values().stream().anyMatch(list -> list.size() > 1)) {
									// Found more than one match in the database
									throwException("Ambiguous match detected");
								}
								toCreateSubgraph.addAll(removedIds);
								objectIdMap.putAll(objectIdMapSubgraph.entrySet().stream()
										.collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get(0))));
								System.out.println("ADD: " + toCreateSubgraph.size() + " / " + subGraph.size()); // DEBUG
							} else {
								System.out.println("FAIL B"); // DEBUG
							}
						} else {
							System.out.println("FAIL A"); // DEBUG
						}
					}
					nDuplicates++; // Increase acceptable number of extra Classes with duplicate values
					if (nDuplicates > allClasses.size() - classesWithDuplicates.size()) {
						nDuplicates = 0;
						nObjects++; // Increase number of created Objects
					}
					if (nObjects > matchedByDescriptors.size()) {
						// Failsafe if no match found in the database
						toCreateSubgraph.addAll(subGraphById.keySet());
						System.out.println("FAILSAFE"); // DEBUG
					}
				}
				classesWithDuplicates.addAll(getNewClassesWithDuplicates(toCreateSubgraph, allMatchedByDescriptors, createdByClass, classesWithDuplicates, graphSONObjects));
				toCreate.addAll(toCreateSubgraph);
				for (String dataId : toCreateSubgraph) {
					createdByClass.computeIfAbsent(graphSONObjects.get(dataId).className, key -> new ArrayList<>()).add(dataId);
				}
			}
		}

		// Create Objects including their Descriptors and Relationships in the database
		for (String dataId : toCreate) {
			// TODO create Object and its Descriptors, add id to objectIdMap
			System.out.println("CREATE: " + graphSONObjects.get(dataId)); // DEBUG
		}
		// TODO create Relationships for all newly created Objects using objectIdMap
	}

	private Set<String> getClassesWithDuplicates(Transaction tx, String namespaceName, Set<String> allClasses) {
		Set<String> classesWithDuplicates = new HashSet<>();
		if (!allClasses.isEmpty()) {
			String query = """
				MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(class:Class)
				WHERE class.name IN $allClasses
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
				"allClasses", allClasses
			));
			if (result.hasNext()) {
				Map<String, Object> record = result.next();
				classesWithDuplicates.addAll((List<String>) record.get("classNames"));
			}
		}
		return classesWithDuplicates;
	}
	
	private Set<String> getNewClassesWithDuplicates(Set<String> newDataIds, Set<String> matchedDataIds, Map<String, List<String>> createdByClass, Set<String> classesWithDuplicates, Map<String, GraphSONData.GraphsonObject> graphSONObjects) {
		// Count number of new Classes with duplicate values if Objects from newDataIds are added to the database
		Set<String> newDuplicates = new HashSet<>();
		for (String dataId : newDataIds) {
			String className = graphSONObjects.get(dataId).className;
			if (!classesWithDuplicates.contains(className)) {
				if (matchedDataIds.contains(dataId) || duplicateCreatedInClass(dataId, className, createdByClass, graphSONObjects) ) {
					// New duplicate created
					newDuplicates.add(className);
				}
			}
		}
		return newDuplicates;
	}

	private boolean duplicateCreatedInClass(String dataId, String className, Map<String, List<String>> createdByClass, Map<String, GraphSONData.GraphsonObject> graphSONObjects) {
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
}
