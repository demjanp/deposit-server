package deposit.service.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class QueryTranslatorTest {

    @Test
    public void testSimpleSelectWhere() {
        // Given a simple query with class and descriptor
        String depositQuery = "SELECT C1.D1, C2.D2 AS alias WHERE C1.D1 = 'value1' AND alias = 'value2'";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_alias:Object)-[:MEMBER_OF]->(:Class {name: 'C2'}), " +
                          "(o_alias)-[:HAS_DESCRIPTOR]-(d_alias:Descriptor {name: 'D2'})\n" +
                          "WITH d_C1_D1, d_alias, o_alias, o_C1\n" +
                          "OPTIONAL MATCH (o_C1)-[:RELATED_TO*]-(o_alias)\n" +
                          "WITH d_C1_D1\n" +
                          "WHERE (d_C1_D1 = 'value1' AND d_alias = 'value2')\n";

        assertEquals(expected, cypher);
    }

    @Test
    public void testSelectWithRelationship() {
        // Given a query with a relationship between two classes
        String depositQuery = "SELECT C1.D1, C2.D2 AS alias WHERE C1-contains->C2";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'}), " +
                          "(o_C1)-[:HAS_DESCRIPTOR {name: 'D1'}]-(d_C1_D1:Descriptor), " +
                          "(o_C2)-[:HAS_DESCRIPTOR {name: 'D2'}]-(d_alias:Descriptor)\n" +
                          "WITH o_C1, o_C2\n" +
                          "OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO {label: 'contains'}]->(o_C2)\n";

        System.out.println(expected);
        System.out.println(cypher);
        assertEquals(expected, cypher);
    }

    @Test
    public void testSelectWithAliasAndRelationship() {
        // Given a query with an alias and a relationship between two classes
        String depositQuery = "SELECT C1.D1, C2 AS alias WHERE C1-contains->alias";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_alias:Object)-[:MEMBER_OF]->(:Class {name: 'C2'}), " +
                          "(o_C1)-[:HAS_DESCRIPTOR {name: 'D1'}]-(d_C1_D1:Descriptor)\n" +
                          "WITH o_C1, o_alias\n" +
                          "OPTIONAL MATCH (o_C1)-[r_C1_alias:RELATED_TO {label: 'contains'}]->(o_alias)\n";

        // Then the Cypher query should match the expected result
        assertEquals(expected, cypher);
    }

    @Test
    public void testSelectWithWhereAndNotCondition() {
        // Given a query with a NOT condition in the WHERE clause
        String depositQuery = "SELECT C1.D1 WHERE NOT (C1.D1 = 'value1')";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'})\n" +
                          "WITH d_C1_D1\n" +
                          "WHERE (NOT (d_C1_D1 = 'value1'))\n";

        // Then the Cypher query should match the expected result
        assertEquals(expected, cypher);
    }

    @Test
    public void testSelectWithCompositeCondition() {
        String depositQuery = "SELECT C1.D1, C2.D2 WHERE (C1.D1 = 'value1' OR C2.D2 = 'value2') AND C3.D3 = 'value3'";

        String cypher = QueryTranslator.translateToCypher(depositQuery);

        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'}), " +
                          "(o_C2)-[:HAS_DESCRIPTOR]-(d_C2_D2:Descriptor {name: 'D2'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C3:Object)-[:MEMBER_OF]->(:Class {name: 'C3'}), " +
                          "(o_C3)-[:HAS_DESCRIPTOR]-(d_C3_D3:Descriptor {name: 'D3'})\n" +
                          "WITH d_C1_D1, d_C2_D2, d_C3_D3, o_C2, o_C1\n" +
                          "OPTIONAL MATCH (o_C1)-[:RELATED_TO*]-(o_C2)\n" +
                          "WITH d_C1_D1, d_C2_D2, d_C3_D3\n" +
                          "WHERE ((d_C1_D1 = 'value1' OR d_C2_D2 = 'value2') AND d_C3_D3 = 'value3')\n";

        assertEquals(expected, cypher);
    }
}
