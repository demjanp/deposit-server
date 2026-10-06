package deposit.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphSONDataTest {

	private Map<String, GraphSONData.GSElement> graphsonObjects;

	@BeforeEach
	void setUp() {
		graphsonObjects = new HashMap<>();
	}

	@Test
	void testFindSubgraphs_singleNode() {
		GraphSONData.GSObject node = new GraphSONData.GSObject();
		node.id = "1";
		node.className = "NodeClass";
		node.relationships = new ArrayList<>();
		graphsonObjects.put(node.id, node);

		List<List<GraphSONData.GSElement>> subgraphs = GraphSONData.findSubgraphs(graphsonObjects);
		assertEquals(1, subgraphs.size(), "There should be 1 component.");
		assertEquals(1, subgraphs.get(0).size(), "The component should contain 1 node.");
		assertEquals("1", ((GraphSONData.GSObject) subgraphs.get(0).get(0)).id, "The node ID should be '1'.");
	}

	@Test
	void testFindSubgraphs_twoConnectedNodes() {
		GraphSONData.GSObject node1 = new GraphSONData.GSObject();
		node1.id = "1";
		node1.className = "NodeClass1";
		node1.relationships = new ArrayList<>();

		GraphSONData.GSObject node2 = new GraphSONData.GSObject();
		node2.id = "2";
		node2.className = "NodeClass2";
		node2.relationships = new ArrayList<>();

		GraphSONData.GSRelationship relationship = new GraphSONData.GSRelationship();
		relationship.label = "related_to";
		relationship.targetId = "2";
		node1.relationships.add(relationship);

		graphsonObjects.put(node1.id, node1);
		graphsonObjects.put(node2.id, node2);

		List<List<GraphSONData.GSElement>> subgraphs = GraphSONData.findSubgraphs(graphsonObjects);
		assertEquals(1, subgraphs.size(), "There should be 1 component.");
		assertEquals(2, subgraphs.get(0).size(), "The component should contain 2 nodes.");
	}

	@Test
	void testFindSubgraphs_twoDisconnectedNodes() {
		GraphSONData.GSObject node1 = new GraphSONData.GSObject();
		node1.id = "1";
		node1.className = "NodeClass1";
		node1.relationships = new ArrayList<>();

		GraphSONData.GSObject node2 = new GraphSONData.GSObject();
		node2.id = "2";
		node2.className = "NodeClass2";
		node2.relationships = new ArrayList<>();

		graphsonObjects.put(node1.id, node1);
		graphsonObjects.put(node2.id, node2);

		List<List<GraphSONData.GSElement>> subgraphs = GraphSONData.findSubgraphs(graphsonObjects);
		assertEquals(2, subgraphs.size(), "There should be 2 components.");
		assertEquals(1, subgraphs.get(0).size(), "Each component should contain 1 node.");
		assertEquals(1, subgraphs.get(1).size(), "Each component should contain 1 node.");
	}

	@Test
	void testFindSubgraphs_threeNodesTwoConnected() {
		GraphSONData.GSObject node1 = new GraphSONData.GSObject();
		node1.id = "1";
		node1.className = "NodeClass1";
		node1.relationships = new ArrayList<>();

		GraphSONData.GSObject node2 = new GraphSONData.GSObject();
		node2.id = "2";
		node2.className = "NodeClass2";
		node2.relationships = new ArrayList<>();

		GraphSONData.GSObject node3 = new GraphSONData.GSObject();
		node3.id = "3";
		node3.className = "NodeClass3";
		node3.relationships = new ArrayList<>();

		GraphSONData.GSRelationship relationship = new GraphSONData.GSRelationship();
		relationship.label = "related_to";
		relationship.targetId = "2";
		node1.relationships.add(relationship);

		graphsonObjects.put(node1.id, node1);
		graphsonObjects.put(node2.id, node2);
		graphsonObjects.put(node3.id, node3);

		List<List<GraphSONData.GSElement>> subgraphs = GraphSONData.findSubgraphs(graphsonObjects);
		assertEquals(2, subgraphs.size(), "There should be 2 components.");
		assertEquals(2, subgraphs.get(0).size(), "One component should contain 2 nodes.");
		assertEquals(1, subgraphs.get(1).size(), "One component should contain 1 node.");
	}

	@Test
	void testFindSubgraphs_largeComplicatedGraph() {
		// Create multiple nodes
		GraphSONData.GSObject node1 = new GraphSONData.GSObject();
		node1.id = "1";
		node1.className = "NodeClass1";
		node1.relationships = new ArrayList<>();

		GraphSONData.GSObject node2 = new GraphSONData.GSObject();
		node2.id = "2";
		node2.className = "NodeClass2";
		node2.relationships = new ArrayList<>();

		GraphSONData.GSObject node3 = new GraphSONData.GSObject();
		node3.id = "3";
		node3.className = "NodeClass3";
		node3.relationships = new ArrayList<>();

		GraphSONData.GSObject node4 = new GraphSONData.GSObject();
		node4.id = "4";
		node4.className = "NodeClass4";
		node4.relationships = new ArrayList<>();

		GraphSONData.GSObject node5 = new GraphSONData.GSObject();
		node5.id = "5";
		node5.className = "NodeClass5";
		node5.relationships = new ArrayList<>();

		GraphSONData.GSObject node6 = new GraphSONData.GSObject();
		node6.id = "6";
		node6.className = "NodeClass6";
		node6.relationships = new ArrayList<>();

		GraphSONData.GSObject node7 = new GraphSONData.GSObject();
		node7.id = "7";
		node7.className = "NodeClass7";
		node7.relationships = new ArrayList<>();

		// Create GSRelationships between nodes
		GraphSONData.GSRelationship rel1to2 = new GraphSONData.GSRelationship();
		rel1to2.label = "related_to";
		rel1to2.targetId = "2";
		node1.relationships.add(rel1to2);

		GraphSONData.GSRelationship rel2to3 = new GraphSONData.GSRelationship();
		rel2to3.label = "related_to";
		rel2to3.targetId = "3";
		node2.relationships.add(rel2to3);

		GraphSONData.GSRelationship rel4to5 = new GraphSONData.GSRelationship();
		rel4to5.label = "related_to";
		rel4to5.targetId = "5";
		node4.relationships.add(rel4to5);

		GraphSONData.GSRelationship rel5to6 = new GraphSONData.GSRelationship();
		rel5to6.label = "related_to";
		rel5to6.targetId = "6";
		node5.relationships.add(rel5to6);

		GraphSONData.GSRelationship rel6to7 = new GraphSONData.GSRelationship();
		rel6to7.label = "related_to";
		rel6to7.targetId = "7";
		node6.relationships.add(rel6to7);

		// Add all nodes to the graph
		graphsonObjects.put(node1.id, node1);
		graphsonObjects.put(node2.id, node2);
		graphsonObjects.put(node3.id, node3);
		graphsonObjects.put(node4.id, node4);
		graphsonObjects.put(node5.id, node5);
		graphsonObjects.put(node6.id, node6);
		graphsonObjects.put(node7.id, node7);

		// Run the findSubgraphs method
		List<List<GraphSONData.GSElement>> subgraphs = GraphSONData.findSubgraphs(graphsonObjects);

		// Check that there are 2 components (one with nodes 1-3, another with nodes 4-7)
		assertEquals(2, subgraphs.size(), "There should be 2 components.");
		
		// Component 1 should have nodes 1, 2, 3
		List<String> component1Ids = getIdsFromComponent(subgraphs.get(0));
		assertTrue(component1Ids.containsAll(List.of("1", "2", "3")), "First component should contain nodes 1, 2, 3");

		// Component 2 should have nodes 4, 5, 6, 7
		List<String> component2Ids = getIdsFromComponent(subgraphs.get(1));
		assertTrue(component2Ids.containsAll(List.of("4", "5", "6", "7")), "Second component should contain nodes 4, 5, 6, 7");
	}

	// Helper method to get the IDs of nodes in a component
	private List<String> getIdsFromComponent(List<GraphSONData.GSElement> component) {
		List<String> ids = new ArrayList<>();
		for (GraphSONData.GSElement obj : component) {
			ids.add(((GraphSONData.GSObject) obj).id);
		}
		return ids;
	}
}
