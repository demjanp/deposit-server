package deposit.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphSONDataTest {

	private Map<String, GraphSONData.GraphsonObject> graphsonObjects;

	@BeforeEach
	void setUp() {
		graphsonObjects = new HashMap<>();
	}

	@Test
	void testFindSubgraphs_singleNode() {
		GraphSONData.GraphsonObject node = new GraphSONData.GraphsonObject();
		node.id = "1";
		node.className = "NodeClass";
		node.relatedTo = new ArrayList<>();
		graphsonObjects.put(node.id, node);

		List<List<GraphSONData.GraphsonObject>> subgraphs = GraphSONData.findSubgraphs(graphsonObjects);
		assertEquals(1, subgraphs.size(), "There should be 1 component.");
		assertEquals(1, subgraphs.get(0).size(), "The component should contain 1 node.");
		assertEquals("1", subgraphs.get(0).get(0).id, "The node ID should be '1'.");
	}

	@Test
	void testFindSubgraphs_twoConnectedNodes() {
		GraphSONData.GraphsonObject node1 = new GraphSONData.GraphsonObject();
		node1.id = "1";
		node1.className = "NodeClass";
		node1.relatedTo = new ArrayList<>();

		GraphSONData.GraphsonObject node2 = new GraphSONData.GraphsonObject();
		node2.id = "2";
		node2.className = "NodeClass";
		node2.relatedTo = new ArrayList<>();

		GraphSONData.RelatedTo relationship = new GraphSONData.RelatedTo();
		relationship.label = "related_to";
		relationship.targetId = "2";
		node1.relatedTo.add(relationship);

		graphsonObjects.put(node1.id, node1);
		graphsonObjects.put(node2.id, node2);

		List<List<GraphSONData.GraphsonObject>> subgraphs = GraphSONData.findSubgraphs(graphsonObjects);
		assertEquals(1, subgraphs.size(), "There should be 1 component.");
		assertEquals(2, subgraphs.get(0).size(), "The component should contain 2 nodes.");
	}

	@Test
	void testFindSubgraphs_twoDisconnectedNodes() {
		GraphSONData.GraphsonObject node1 = new GraphSONData.GraphsonObject();
		node1.id = "1";
		node1.className = "NodeClass";
		node1.relatedTo = new ArrayList<>();

		GraphSONData.GraphsonObject node2 = new GraphSONData.GraphsonObject();
		node2.id = "2";
		node2.className = "NodeClass";
		node2.relatedTo = new ArrayList<>();

		graphsonObjects.put(node1.id, node1);
		graphsonObjects.put(node2.id, node2);

		List<List<GraphSONData.GraphsonObject>> subgraphs = GraphSONData.findSubgraphs(graphsonObjects);
		assertEquals(2, subgraphs.size(), "There should be 2 components.");
		assertEquals(1, subgraphs.get(0).size(), "Each component should contain 1 node.");
		assertEquals(1, subgraphs.get(1).size(), "Each component should contain 1 node.");
	}

	@Test
	void testFindSubgraphs_threeNodesTwoConnected() {
		GraphSONData.GraphsonObject node1 = new GraphSONData.GraphsonObject();
		node1.id = "1";
		node1.className = "NodeClass";
		node1.relatedTo = new ArrayList<>();

		GraphSONData.GraphsonObject node2 = new GraphSONData.GraphsonObject();
		node2.id = "2";
		node2.className = "NodeClass";
		node2.relatedTo = new ArrayList<>();

		GraphSONData.GraphsonObject node3 = new GraphSONData.GraphsonObject();
		node3.id = "3";
		node3.className = "NodeClass";
		node3.relatedTo = new ArrayList<>();

		GraphSONData.RelatedTo relationship = new GraphSONData.RelatedTo();
		relationship.label = "related_to";
		relationship.targetId = "2";
		node1.relatedTo.add(relationship);

		graphsonObjects.put(node1.id, node1);
		graphsonObjects.put(node2.id, node2);
		graphsonObjects.put(node3.id, node3);

		List<List<GraphSONData.GraphsonObject>> subgraphs = GraphSONData.findSubgraphs(graphsonObjects);
		assertEquals(2, subgraphs.size(), "There should be 2 components.");
		assertEquals(2, subgraphs.get(0).size(), "One component should contain 2 nodes.");
		assertEquals(1, subgraphs.get(1).size(), "One component should contain 1 node.");
	}

	@Test
	void testFindSubgraphs_largeComplicatedGraph() {
		// Create multiple nodes
		GraphSONData.GraphsonObject node1 = new GraphSONData.GraphsonObject();
		node1.id = "1";
		node1.className = "NodeClass";
		node1.relatedTo = new ArrayList<>();

		GraphSONData.GraphsonObject node2 = new GraphSONData.GraphsonObject();
		node2.id = "2";
		node2.className = "NodeClass";
		node2.relatedTo = new ArrayList<>();

		GraphSONData.GraphsonObject node3 = new GraphSONData.GraphsonObject();
		node3.id = "3";
		node3.className = "NodeClass";
		node3.relatedTo = new ArrayList<>();

		GraphSONData.GraphsonObject node4 = new GraphSONData.GraphsonObject();
		node4.id = "4";
		node4.className = "NodeClass";
		node4.relatedTo = new ArrayList<>();

		GraphSONData.GraphsonObject node5 = new GraphSONData.GraphsonObject();
		node5.id = "5";
		node5.className = "NodeClass";
		node5.relatedTo = new ArrayList<>();

		GraphSONData.GraphsonObject node6 = new GraphSONData.GraphsonObject();
		node6.id = "6";
		node6.className = "NodeClass";
		node6.relatedTo = new ArrayList<>();

		GraphSONData.GraphsonObject node7 = new GraphSONData.GraphsonObject();
		node7.id = "7";
		node7.className = "NodeClass";
		node7.relatedTo = new ArrayList<>();

		// Create relationships between nodes
		GraphSONData.RelatedTo rel1to2 = new GraphSONData.RelatedTo();
		rel1to2.label = "related_to";
		rel1to2.targetId = "2";
		node1.relatedTo.add(rel1to2);

		GraphSONData.RelatedTo rel2to3 = new GraphSONData.RelatedTo();
		rel2to3.label = "related_to";
		rel2to3.targetId = "3";
		node2.relatedTo.add(rel2to3);

		GraphSONData.RelatedTo rel4to5 = new GraphSONData.RelatedTo();
		rel4to5.label = "related_to";
		rel4to5.targetId = "5";
		node4.relatedTo.add(rel4to5);

		GraphSONData.RelatedTo rel5to6 = new GraphSONData.RelatedTo();
		rel5to6.label = "related_to";
		rel5to6.targetId = "6";
		node5.relatedTo.add(rel5to6);

		GraphSONData.RelatedTo rel6to7 = new GraphSONData.RelatedTo();
		rel6to7.label = "related_to";
		rel6to7.targetId = "7";
		node6.relatedTo.add(rel6to7);

		// Add all nodes to the graph
		graphsonObjects.put(node1.id, node1);
		graphsonObjects.put(node2.id, node2);
		graphsonObjects.put(node3.id, node3);
		graphsonObjects.put(node4.id, node4);
		graphsonObjects.put(node5.id, node5);
		graphsonObjects.put(node6.id, node6);
		graphsonObjects.put(node7.id, node7);

		// Run the findSubgraphs method
		List<List<GraphSONData.GraphsonObject>> subgraphs = GraphSONData.findSubgraphs(graphsonObjects);

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
	private List<String> getIdsFromComponent(List<GraphSONData.GraphsonObject> component) {
		List<String> ids = new ArrayList<>();
		for (GraphSONData.GraphsonObject obj : component) {
			ids.add(obj.id);
		}
		return ids;
	}
}
