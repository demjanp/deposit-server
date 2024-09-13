package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import deposit.common.result.QueryResult;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class GetQueryAPITest extends BaseNeo4jTest {

    private String token;

    @Before
    public void setUp() {
        super.setUp();
        token = ServerContext.getInstance().generateToken("testUser");
    }

    private void ensureNamespaceExists(Session session, String namespaceName) {
        session.writeTransaction(tx -> {
            // Check if the namespace exists
            Result result = tx.run("MATCH (n:Namespace {name: $namespaceName}) RETURN n",
                                   Map.of("namespaceName", namespaceName));
            if (!result.hasNext()) {
                // If it doesn't exist, create the namespace
                tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)",
                       Map.of("namespaceName", namespaceName, "token", token));
            }
            // Set it as the active namespace
            tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)",
                   Map.of("namespaceName", namespaceName, "token", token));
            tx.commit();
            return null;
        });
    }

    private String createObject(Session session, String className) {
        return session.writeTransaction(tx -> {
            Result result = tx.run("CALL deposit.create.object($className, [], [], $token) YIELD node RETURN node.id AS objectId",
                                   Map.of("className", className, "token", token));
            String objectId = result.single().get("objectId").asString();
            tx.commit();
            return objectId;
        });
    }

    private String createDescriptor(Session session, String objectId, String descriptorName, Object value) {
        return session.writeTransaction(tx -> {
            Result result = tx.run("CALL deposit.set.descriptor($objectId, $descriptorName, $value, $token) YIELD node RETURN node.id AS descriptorId",
                   Map.of("objectId", objectId, "descriptorName", descriptorName, "value", value, "token", token));
            String descriptorId = result.single().get("descriptorId").asString();
            tx.commit();
            return descriptorId;
        });
    }

    private String createRelationship(Session session, String objectId1, String label, String objectId2) {
        return session.writeTransaction(tx -> {
            Result result = tx.run("CALL deposit.create.relationship($objectId1, $objectId2, $label, null, $token) YIELD rel RETURN rel.id AS relationshipId",
                       Map.of("objectId1", objectId1, "objectId2", objectId2, "label", label, "token", token));
            String relId = result.single().get("relationshipId").asString();
            tx.commit();
            return relId;
        });
    }

    @Test
    public void shouldReturnQueryResults() {
        try (Session session = driver.session()) {
            String namespaceName = "TestNamespace";
            String className1 = "C1";
            String className2 = "C2";

            // Ensure the namespace exists and set it as active
            ensureNamespaceExists(session, namespaceName);

            // Create objects and descriptors
            String objectId1 = createObject(session, className1);
            String objectId2 = createObject(session, className2);
            String objectId3 = createObject(session, className1);
            String objectId4 = createObject(session, className2);

            createRelationship(session, objectId1, "contains", objectId2);

            Result queryResult = session.run(
                    "CALL deposit.get.query('SELECT C1, C2 ORDER BY C2', $token) YIELD result RETURN result",
                    Map.of("token", token)
            );
            List<String[]> expectedPairs = new ArrayList<>();
            expectedPairs.add(new String[]{objectId1, objectId2});
            expectedPairs.add(new String[]{objectId3, null});
            int index = 0;
            while (queryResult.hasNext()) {
                Map<String, Object> result = queryResult.next().get("result").asMap();
                Node node1 = (Node) result.get("C1");
                Node node2 = (Node) result.get("C2");
                String id1 = node1 == null ? null : node1.get("id").asString();
                String id2 = node2 == null ? null : node2.get("id").asString();
                String[] expected = expectedPairs.get(index);
                index++;
                assertEquals(expected[0], id1);
                assertEquals(expected[1], id2);
            }
            assertEquals("Number of returned results doesn't match expected pairs.", expectedPairs.size(), index);
        }
    }

    @Test
    public void shouldReturnQueryResultsWithDescriptors() {
        try (Session session = driver.session()) {
            String namespaceName = "TestNamespace";
            String className1 = "C1";
            String className2 = "C2";
            String descriptorName1 = "D1";
            String descriptorName2 = "D2";

            // Ensure the namespace exists and set it as active
            ensureNamespaceExists(session, namespaceName);

            // Create objects and descriptors
            String objectId1 = createObject(session, className1);
            String objectId2 = createObject(session, className2);
            String objectId3 = createObject(session, className1);
            String objectId4 = createObject(session, className2);

            createDescriptor(session, objectId1, descriptorName1, "value1");
            createDescriptor(session, objectId2, descriptorName2, "value2");
            createDescriptor(session, objectId3, descriptorName1, "value3");
            createDescriptor(session, objectId4, descriptorName2, "value4");
            
            createRelationship(session, objectId1, "contains", objectId2);
            createRelationship(session, objectId1, "contains", objectId4);

            Result queryResult = session.run(
                    "CALL deposit.get.query('SELECT C1.D1, C2.D2 ORDER BY C1.D1, C2.D2', $token) YIELD result RETURN result",
                    Map.of("token", token)
            );

            List<String[]> expectedPairs = new ArrayList<>();
            expectedPairs.add(new String[]{"value1", "value2"});
            expectedPairs.add(new String[]{"value1", "value4"});
            expectedPairs.add(new String[]{"value3", null});

            int index = 0;
            while (queryResult.hasNext()) {
                Map<String, Object> mapResult = queryResult.next().get("result").asMap();
                Node node1 = (Node) mapResult.get("C1.D1");
                Node node2 = (Node) mapResult.get("C2.D2");
                String value1 = node1 == null ? null : node1.get("value").asString();
                String value2 = node2 == null ? null : node2.get("value").asString();
                String[] expected = expectedPairs.get(index);
                index++;
                assertEquals(expected[0], value1);
                assertEquals(expected[1], value2);
            }
            assertEquals("Number of returned results doesn't match expected pairs.", expectedPairs.size(), index);
        }
    }

    @Test
    public void shouldReturnFilteredQueryResultsWithWhereClause() {
        try (Session session = driver.session()) {
            String namespaceName = "TestNamespace";
            String className1 = "C1";
            String className2 = "C2";
            String descriptorName1 = "D1";
            String descriptorName2 = "D2";

            // Ensure the namespace exists and set it as active
            ensureNamespaceExists(session, namespaceName);

            // Create objects and descriptors
            String objectId1 = createObject(session, className1);
            String objectId2 = createObject(session, className2);
            String objectId3 = createObject(session, className2);
            String objectId4 = createObject(session, className2);

            // Create descriptors and get their IDs
            String descriptorId1 = createDescriptor(session, objectId1, descriptorName1, "value1");
            String descriptorId2 = createDescriptor(session, objectId2, descriptorName2, "value2");
            String descriptorId3 = createDescriptor(session, objectId3, descriptorName2, "value2");
            String descriptorId4 = createDescriptor(session, objectId4, descriptorName2, "value4");

            // Create relationships
            createRelationship(session, objectId1, "contains", objectId2);
            createRelationship(session, objectId1, "contains", objectId3);
            createRelationship(session, objectId1, "contains", objectId4);

            // Run the query with the WHERE clause
            Result queryResult = session.run(
                    "CALL deposit.get.query('SELECT C1.D1, C2.D2 WHERE C2.D2 = \"value2\"', $token) YIELD result RETURN result",
                    Map.of("token", token)
            );

            // Iterate over all results to verify that only descriptorId2 and descriptorId3 are returned
            while (queryResult.hasNext()) {
                Map<String, Object> result = queryResult.next().get("result").asMap();

                assertTrue(result.containsKey("C1.D1"));
                assertTrue(result.containsKey("C2.D2"));

                Node descriptorNode = (Node) result.get("C2.D2");

                assertNotNull(descriptorNode);

                // Check that the returned descriptor ID matches either descriptorId2 or descriptorId3
                String returnedDescriptorId = descriptorNode.get("id").asString();
                assertTrue(returnedDescriptorId.equals(descriptorId2) || returnedDescriptorId.equals(descriptorId3));

                // Ensure descriptorId4 is not included
                assertFalse(returnedDescriptorId.equals(descriptorId4));
            }
        }
    }

}
