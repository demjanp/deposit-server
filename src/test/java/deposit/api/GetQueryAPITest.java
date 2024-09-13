package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import deposit.common.result.QueryResult;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.neo4j.driver.types.Node;

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

            createDescriptor(session, objectId1, "D1", "value1");
            createDescriptor(session, objectId2, "D2", "value2");
            createDescriptor(session, objectId3, "D1", "value3");
            createDescriptor(session, objectId4, "D2", "value4");

            createRelationship(session, objectId1, "contains", objectId2);

            /*
            Result queryResult = session.run(
                    "CALL deposit.get.query('SELECT C1, C2', $token) YIELD result RETURN result",
                    Map.of("token", token)
            );

            while (queryResult.hasNext()) {
                Map<String, Object> result = queryResult.next().get("result").asMap();
                System.out.println(result);
                Node node1 = (Node) result.get("C1");
                Node node2 = (Node) result.get("C2");
                System.out.println(node1.get("id").asString() + " : " + node2.get("id").asString());
            }

            */

            Result queryResult = session.run(
            "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), (o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'})\n" +
                    "WITH o_C1, d_C1_D1\n" +
                    "OPTIONAL MATCH (o_C1)-[:RELATED_TO*]-(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'}), (o_C2)-[:HAS_DESCRIPTOR]-(d_C2_D2:Descriptor {name: 'D2'})\n" +
                    "WITH o_C1, d_C1_D1, o_C2, d_C2_D2\n" +
                    "RETURN d_C1_D1.value AS C1_D1, d_C2_D2.value AS D2_C2",
                    Map.of("namespaceName", namespaceName)
            );
            while (queryResult.hasNext()) {
                Record record = queryResult.next();
                System.out.println(record);
            }

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

            Result queryResult = session.run(
                    "CALL deposit.get.query('SELECT C1.D1, C2.D2', $token) YIELD result RETURN result",
                    Map.of("token", token)
            );

            while (queryResult.hasNext()) {
                Map<String, Object> result = queryResult.next().get("result").asMap();
                System.out.println(result); // DEBUG

                Node node1 = (Node) result.get("C1.D1");
                Node node2 = (Node) result.get("C2.D2");
                System.out.println(node1.get("value").asString() + " : " + node2.get("value").asString());
            }

            // Check the results
            assertTrue(queryResult.hasNext());
            Map<String, Object> result = queryResult.next().get("result").asMap();

            // Validate the returned nodes (using org.neo4j.driver.types.Node)
            assertTrue(result.containsKey("C1.D1"));
            assertTrue(result.containsKey("C2.D2"));

            // Retrieve nodes from the result map
            Node node1 = (Node) result.get("C1.D1");
            Node node2 = (Node) result.get("C2.D2");

            assertNotNull(node1);
            assertNotNull(node2);

            // Validate the node properties
            assertEquals("value1", node1.get("value").asString());
            assertEquals("value2", node2.get("value").asString());

            // Validate the keys
            assertTrue(result.keySet().contains("C1.D1"));
            assertTrue(result.keySet().contains("C2.D2"));
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
