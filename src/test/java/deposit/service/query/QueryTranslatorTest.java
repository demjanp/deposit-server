package deposit.service.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class QueryTranslatorTest {

    @Test
    public void testSelectSingleClass() {
        // Given a simple query with class and descriptor
        String depositQuery = "SELECT C1";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
                          "WITH o_C1\n" +
                          "RETURN o_C1 AS C1";

        assertEquals(expected, cypher);
    }

    @Test
    public void testSelectClassAndDescriptor() {
        // Given a simple query with class and descriptor
        String depositQuery = "SELECT C1.D1";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'})\n" +
                          "WITH d_C1_D1, o_C1\n" +
                          "RETURN d_C1_D1 AS C1_D1";

        assertEquals(expected, cypher);
    }

    @Test
    public void testSelectAlias() {
        // Given a simple query with class and descriptor
        String depositQuery = "SELECT C1.D1 AS alias";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_alias:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(o_alias)-[:HAS_DESCRIPTOR]-(d_alias:Descriptor {name: 'D1'})\n" +
                          "WITH d_alias, o_alias\n" +
                          "RETURN d_alias AS alias";

        assertEquals(expected, cypher);
    }

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
                          "WITH d_C1_D1, d_alias, o_alias, o_C1\n" +
                          "WHERE (d_C1_D1 = 'value1' AND d_alias = 'value2')\n" +
                          "RETURN d_C1_D1 AS C1_D1, d_alias AS alias";

        assertEquals(expected, cypher);
    }

    @Test
    public void testSelectWithC2InWhere() {
        // Given a simple query with class and descriptor
        String depositQuery = "SELECT C1 WHERE C1.D1 = 'value1' AND C2.D2 = 'value2'";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'}), " +
                          "(o_C2)-[:HAS_DESCRIPTOR]-(d_C2_D2:Descriptor {name: 'D2'})\n" +
                          "WITH d_C1_D1, d_C2_D2, o_C2, o_C1\n" +
                          "OPTIONAL MATCH (o_C1)-[:RELATED_TO*]-(o_C2)\n" +
                          "WITH d_C1_D1, d_C2_D2, o_C2, o_C1\n" +
                          "WHERE (d_C1_D1 = 'value1' AND d_C2_D2 = 'value2')\n" +
                          "RETURN o_C1 AS C1";

        assertEquals(expected, cypher);
    }

    @Test
    public void testSelectQuoted() {
        // Given a query with a relationship between two classes
        String depositQuery = "SELECT C1.D1, \"Class 2\".D2, \"Class 3\".\"Descr 3\"";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_Class_2:Object)-[:MEMBER_OF]->(:Class {name: 'Class 2'}), " +
                          "(o_Class_2)-[:HAS_DESCRIPTOR]-(d_Class_2_D2:Descriptor {name: 'D2'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_Class_3:Object)-[:MEMBER_OF]->(:Class {name: 'Class 3'}), " +
                          "(o_Class_3)-[:HAS_DESCRIPTOR]-(d_Class_3_Descr_3:Descriptor {name: 'Descr 3'})\n" +
                          "WITH d_C1_D1, d_Class_2_D2, d_Class_3_Descr_3, o_Class_3, o_C1, o_Class_2\n" +
                          "OPTIONAL MATCH (o_C1)-[:RELATED_TO*]-(o_Class_3), (o_C1)-[:RELATED_TO*]-(o_Class_2)\n" +
                          "WITH d_C1_D1, d_Class_2_D2, d_Class_3_Descr_3, o_Class_3, o_C1, o_Class_2\n" +
                          "RETURN d_C1_D1 AS C1_D1, d_Class_2_D2 AS Class_2_D2, d_Class_3_Descr_3 AS Class_3_Descr_3";

        assertEquals(expected, cypher);
    }

    @Test
    public void testSelectWithCount() {
        // Given a query with a relationship between two classes
        String depositQuery = "SELECT C1.D1, COUNT(C2) WHERE C1-contains->C2";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
                          "WITH d_C1_D1, o_C2, o_C1\n" +
                          "OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO {label: 'contains'}]->(o_C2)\n" +
                          "WITH d_C1_D1, r_C1_C2, o_C2, o_C1\n" +
                          "WHERE (r_C1_C2 IS NOT NULL)\n" +
                          "RETURN d_C1_D1 AS C1_D1, COUNT(o_C2) AS COUNT_C2";

        assertEquals(expected, cypher);
    }

    @Test
    public void testSelectWithCountDistinct() {
        // Given a query with a relationship between two classes
        String depositQuery = "SELECT C1.D1, COUNT(DISTINCT C2) WHERE C1-contains->C2";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
                          "WITH d_C1_D1, o_C2, o_C1\n" +
                          "OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO {label: 'contains'}]->(o_C2)\n" +
                          "WITH d_C1_D1, r_C1_C2, o_C2, o_C1\n" +
                          "WHERE (r_C1_C2 IS NOT NULL)\n" +
                          "RETURN d_C1_D1 AS C1_D1, COUNT(DISTINCT o_C2) AS COUNT_C2";

        assertEquals(expected, cypher);
    }

    @Test
    public void testSelectWithPercentile() {
        // Given a query with a relationship between two classes
        String depositQuery = "SELECT C1.D1, PERCENTILE(C2.D2, 0.25) AS QUART1_D2 WHERE C1-contains->C2";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_QUART1_D2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'}), " +
                          "(o_QUART1_D2)-[:HAS_DESCRIPTOR]-(d_QUART1_D2:Descriptor {name: 'D2'})\n" +
                          "WITH d_C1_D1, o_QUART1_D2, d_QUART1_D2, o_C1\n" +
                          "OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO {label: 'contains'}]->(o_QUART1_D2)\n" +
                          "WITH d_C1_D1, o_QUART1_D2, r_C1_C2, d_QUART1_D2, o_C1\n" +
                          "WHERE (r_C1_C2 IS NOT NULL)\n" +
                          "RETURN d_C1_D1 AS C1_D1, percentileCont(d_QUART1_D2.value, 0.25) AS QUART1_D2";

        assertEquals(expected, cypher);
    }

    @Test
    public void testSelectWithSum() {
        // Given a query with a relationship between two classes
        String depositQuery = "SELECT C1.D1, SUM(C2.D2) WHERE C1-contains->C2";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'}), " +
                          "(o_C2)-[:HAS_DESCRIPTOR]-(d_SUM_C2_D2:Descriptor {name: 'D2'})\n" +
                          "WITH d_C1_D1, d_SUM_C2_D2, o_C2, o_C1\n" +
                          "OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO {label: 'contains'}]->(o_C2)\n" +
                          "WITH d_C1_D1, d_SUM_C2_D2, r_C1_C2, o_C2, o_C1\n" +
                          "WHERE (r_C1_C2 IS NOT NULL)\n" +
                          "RETURN d_C1_D1 AS C1_D1, SUM(d_SUM_C2_D2.value) AS SUM_C2_D2";

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
                          "(o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_alias:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
                          "WITH d_C1_D1, o_alias, o_C1\n" +
                          "OPTIONAL MATCH (o_C1)-[r_C1_alias:RELATED_TO {label: 'contains'}]->(o_alias)\n" +
                          "WITH d_C1_D1, r_C1_alias, o_alias, o_C1\n" +
                          "WHERE (r_C1_alias IS NOT NULL)\n" +
                          "RETURN d_C1_D1 AS C1_D1, o_alias AS alias";

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
                          "WITH d_C1_D1, o_C1\n" +
                          "WHERE (NOT (d_C1_D1 = 'value1'))\n" +
                          "RETURN d_C1_D1 AS C1_D1";

        // Then the Cypher query should match the expected result
        assertEquals(expected, cypher);
    }

    @Test
    public void testSelectWithNestedConditions() {
        // Given a query with nested conditions in the WHERE clause
        String depositQuery = "SELECT C1.D1, C2.D2 WHERE (C1.D1 = 'value1' OR C2.D2 = 'value2') AND (C1-contains->C2) AND NOT (C3.D3 = 'value3')";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'}), " +
                          "(o_C2)-[:HAS_DESCRIPTOR]-(d_C2_D2:Descriptor {name: 'D2'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C3:Object)-[:MEMBER_OF]->(:Class {name: 'C3'}), " +
                          "(o_C3)-[:HAS_DESCRIPTOR]-(d_C3_D3:Descriptor {name: 'D3'})\n" +
                          "WITH d_C1_D1, d_C2_D2, o_C3, d_C3_D3, o_C2, o_C1\n" +
                          "OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO {label: 'contains'}]->(o_C2), (o_C1)-[:RELATED_TO*]-(o_C3)\n" +
                          "WITH d_C1_D1, d_C2_D2, o_C3, d_C3_D3, r_C1_C2, o_C2, o_C1\n" +
                          "WHERE ((d_C1_D1 = 'value1' OR d_C2_D2 = 'value2') AND (r_C1_C2 IS NOT NULL) AND NOT (d_C3_D3 = 'value3'))\n" +
                          "RETURN d_C1_D1 AS C1_D1, d_C2_D2 AS C2_D2";

        // Assert the generated Cypher matches the expected result
        assertEquals(expected, cypher);
    }

    @Test
    public void testSelectWithHopRelationship1() {
        // Given a query with multi-hop relationships
        String depositQuery = "SELECT C1.D1, C2.D2 WHERE C1-*1..3->C2";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'}), " +
                          "(o_C2)-[:HAS_DESCRIPTOR]-(d_C2_D2:Descriptor {name: 'D2'})\n" +
                          "WITH d_C1_D1, d_C2_D2, o_C2, o_C1\n" +
                          "OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO*1..3]->(o_C2)\n" +
                          "WITH d_C1_D1, d_C2_D2, r_C1_C2, o_C2, o_C1\n" +
                          "WHERE (r_C1_C2 IS NOT NULL)\n" +
                          "RETURN d_C1_D1 AS C1_D1, d_C2_D2 AS C2_D2";

        // Assert the generated Cypher matches the expected result
        assertEquals(expected, cypher);
    }

    @Test
    public void testSelectWithHopRelationship2() {
        // Given a query with multi-hop relationships
        String depositQuery = "SELECT C1, C2 WHERE C1-contains*->C2";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
                          "WITH o_C2, o_C1\n" +
                          "OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO* {label: 'contains'}]->(o_C2)\n" +
                          "WITH r_C1_C2, o_C2, o_C1\n" +
                          "WHERE (r_C1_C2 IS NOT NULL)\n" +
                          "RETURN o_C1 AS C1, o_C2 AS C2";

        // Assert the generated Cypher matches the expected result
        assertEquals(expected, cypher);
    }

    @Test
    public void testSelectWithHopRelationship3() {
        // Given a query with multi-hop relationships
        String depositQuery = "SELECT C1, C2 WHERE C1-contains*1..->C2";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
                          "WITH o_C2, o_C1\n" +
                          "OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO*1.. {label: 'contains'}]->(o_C2)\n" +
                          "WITH r_C1_C2, o_C2, o_C1\n" +
                          "WHERE (r_C1_C2 IS NOT NULL)\n" +
                          "RETURN o_C1 AS C1, o_C2 AS C2";

        // Assert the generated Cypher matches the expected result
        assertEquals(expected, cypher);
    }

    @Test
    public void testSelectWithHopRelationship4() {
        // Given a query with multi-hop relationships
        String depositQuery = "SELECT C1, C2 WHERE C1-contains*..3->C2";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
                          "WITH o_C2, o_C1\n" +
                          "OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO*..3 {label: 'contains'}]->(o_C2)\n" +
                          "WITH r_C1_C2, o_C2, o_C1\n" +
                          "WHERE (r_C1_C2 IS NOT NULL)\n" +
                          "RETURN o_C1 AS C1, o_C2 AS C2";

        // Assert the generated Cypher matches the expected result
        assertEquals(expected, cypher);
    }

    @Test
    public void testSelectWithHopRelationship5() {
        // Given a query with multi-hop relationships
        String depositQuery = "SELECT C1, C2 WHERE C1-contains*1..3->C2";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
                          "WITH o_C2, o_C1\n" +
                          "OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO*1..3 {label: 'contains'}]->(o_C2)\n" +
                          "WITH r_C1_C2, o_C2, o_C1\n" +
                          "WHERE (r_C1_C2 IS NOT NULL)\n" +
                          "RETURN o_C1 AS C1, o_C2 AS C2";

        // Assert the generated Cypher matches the expected result
        assertEquals(expected, cypher);
    }

    @Test
    public void testSelectWithAnyRelationship() {
        // Given a query with multi-hop relationships
        String depositQuery = "SELECT C1, C2 WHERE C1-->C2";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
                          "WITH o_C2, o_C1\n" +
                          "OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO]->(o_C2)\n" +
                          "WITH r_C1_C2, o_C2, o_C1\n" +
                          "WHERE (r_C1_C2 IS NOT NULL)\n" +
                          "RETURN o_C1 AS C1, o_C2 AS C2";

        // Assert the generated Cypher matches the expected result
        assertEquals(expected, cypher);
    }

    @Test
    public void testSelectWithHopsAnyRelationship() {
        // Given a query with multi-hop relationships
        String depositQuery = "SELECT C1, C2 WHERE C1-*->C2";

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(depositQuery);

        // Expected Cypher query
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
                          "WITH o_C2, o_C1\n" +
                          "OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO*]->(o_C2)\n" +
                          "WITH r_C1_C2, o_C2, o_C1\n" +
                          "WHERE (r_C1_C2 IS NOT NULL)\n" +
                          "RETURN o_C1 AS C1, o_C2 AS C2";

        // Assert the generated Cypher matches the expected result
        assertEquals(expected, cypher);
    }
}
