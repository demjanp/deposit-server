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

		// TODO make a list of Classes from graphSONObjects which contain duplicate values in the database
		// TODO when checking for extra duplicates, skip Objects from those Classes

		Set<String> toCreate = new HashSet<>(); // Set of dataIds of Objects to create in the database
		Map<String, String> objectIdMap = new HashMap<>(); // Matched Object ids for dataIds {dataId: Object id, ...}
		List<List<GraphSONData.GraphsonObject>> subGraphs = GraphSONData.findSubgraphs(graphSONObjects);
		for (List<GraphSONData.GraphsonObject> subGraph : subGraphs) {
			if (getObjectsByDataRepository.getObjectsByData(tx, namespaceName, subGraph, matchEmpty).isEmpty()) {

				// Find Objects with existing matches in the database based on Class and Descriptors
				List<String> unmatchedByDescriptors = new ArrayList<>();
				List<String> matchedByDescriptors = new ArrayList<>();
				Map<String, GraphSONData.GraphsonObject> subGraphById = new HashMap<>();
				for (GraphSONData.GraphsonObject graphSONObject : subGraph) {
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
						Map<String, GraphSONData.GraphsonObject> reducedSubGraph = new HashMap<>();
						List<String> removedMatched = new ArrayList<>();
						Set<String> removedAll = new HashSet<>();
						for (int index : combinationIndices) {
							String dataId = matchedByDescriptors.get(index);
							reducedSubGraph.put(dataId, subGraphById.get(dataId));
							removedMatched.add(dataId);
							removedAll.add(dataId);
						}
						for (String dataId : unmatchedByDescriptors) {
							reducedSubGraph.put(dataId, subGraphById.get(dataId));
							removedAll.add(dataId);
						}

						// Check if removed Objects were not articulation points
						if (GraphSONData.findSubgraphs(reducedSubGraph).size() == 1) {

							// Check if adding Objects from removedMatched to the database will add <= nDuplicates extra Classes with duplicate values
							if (true) { // TODO add test

								// Check if reduced subGraph exists in the database
								List<GraphSONData.GraphsonObject> reducedSubGraphList = new ArrayList<>(reducedSubGraph.values());
								Map<String, List<String>> objectIdMapSubgraph = getObjectsByDataRepository.getObjectsByData(tx, namespaceName, reducedSubGraphList, matchEmpty);
								if (!objectIdMapSubgraph.isEmpty()) {
									if (!toCreateSubgraph.isEmpty() || objectIdMapSubgraph.values().stream().anyMatch(list -> list.size() > 1)) {
										// Found more than one match in the database
										throwException("Ambiguous match detected");
									}
									toCreateSubgraph.addAll(removedAll);
									objectIdMap.putAll(objectIdMapSubgraph.entrySet().stream()
										.collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get(0))));
								}
							}
						}
					}
					nDuplicates++; // Increase acceptable number of extra Classes with duplicate values
					if (nDuplicates > nObjects) {
						nDuplicates = 0;
						nObjects++; // Increase number of created Objects
					}
					if (nObjects > matchedByDescriptors.size()) {
						// Failsafe if no match found in the database
						toCreateSubgraph.addAll(subGraphById.keySet());
					}
				}
				toCreate.addAll(toCreateSubgraph);
			}
		}

		// Create Objects including their Descriptors and Relationships in the database
		for (String dataId : toCreate) {
			// TODO create Object and its Descriptors, add id to objectIdMap
		}
		// TODO create Relationships for all newly created Objects using objectIdMap
	}
}
