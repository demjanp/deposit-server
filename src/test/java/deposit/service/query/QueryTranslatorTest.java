package deposit.service.query;

import deposit.service.query.elements.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class QueryTranslatorTest {

    @Test
    public void testExplicitRelationshipMatch() {
        // Given a parsed query with an explicit relationship
        ParsedQuery parsedQuery = new ParsedQuery();

        // Define SelectItems for C1 and C2
        SelectItem c1 = new SelectItem("C1", null, "");
        SelectItem c2 = new SelectItem("C2", null, "");
        parsedQuery.addSelectItem(c1);
        parsedQuery.addSelectItem(c2);

        // Define an explicit relationship between C1 and C2
        RelationshipCondition relationship = new RelationshipCondition("o_C1", "o_C2", "C1", "C2", "contains", "OUTGOING", null, null);
        parsedQuery.addRelationshipCondition(relationship);

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(parsedQuery);

        // Then the expected MATCH clause should be present
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'}), " +
                          "(o_C1)-[:contains]->(o_C2)\n";
        assertEquals(expected, cypher);
    }

    @Test
    public void testOptionalMatchForUndefinedRelationships() {
        // Given a parsed query with undefined relationships between C1, C2, and C3
        ParsedQuery parsedQuery = new ParsedQuery();

        // Define SelectItems for C1, C2, and C3
        SelectItem c1 = new SelectItem("C1", null, "");
        SelectItem c2 = new SelectItem("C2", null, "");
        SelectItem c3 = new SelectItem("C3", null, "");
        parsedQuery.addSelectItem(c1);
        parsedQuery.addSelectItem(c2);
        parsedQuery.addSelectItem(c3);

        // Define an explicit relationship between C1 and C2, but none for C3
        RelationshipCondition relationship = new RelationshipCondition("o_C1", "o_C2", "C1", "C2", "contains", "OUTGOING", null, null);
        parsedQuery.addRelationshipCondition(relationship);

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(parsedQuery);

        // Then both MATCH and OPTIONAL MATCH clauses should be present
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'}), " +
                          "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C3:Object)-[:MEMBER_OF]->(:Class {name: 'C3'}), " +
                          "(o_C1)-[:contains]->(o_C2)\n" +
                          "WITH o_C1, o_C2, o_C3\n" +
                          "OPTIONAL MATCH (o_C1)-[:RELATED_TO*]-(o_C3)\n";
        assertEquals(expected, cypher);
    }

    @Test
    public void testClassAndDescriptorMatching() {
        // Given a parsed query with class and descriptor selection
        ParsedQuery parsedQuery = new ParsedQuery();

        // Define SelectItems for C1 with descriptor D1
        SelectItem c1 = new SelectItem("C1", "D1", "");
        parsedQuery.addSelectItem(c1);

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(parsedQuery);

        // Then the expected MATCH clause for class and descriptor should be present
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                          "(o_C1)-[:HAS_DESCRIPTOR {name: 'D1'}]-(d_C1_D1:Descriptor)\n";
        assertEquals(expected, cypher);
    }

    @Test
    public void testNoRelationships() {
        // Given a parsed query with no relationships
        ParsedQuery parsedQuery = new ParsedQuery();

        // Define SelectItems for C1 and C2
        SelectItem c1 = new SelectItem("C1", null, "");
        SelectItem c2 = new SelectItem("C2", null, "");
        parsedQuery.addSelectItem(c1);
        parsedQuery.addSelectItem(c2);

        // When the query is translated to Cypher
        String cypher = QueryTranslator.translateToCypher(parsedQuery);

        // Then only the MATCH clause for classes should be present, no relationships
        String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'}), " +
                "(:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
                "WITH o_C1, o_C2\n" +
                "OPTIONAL MATCH (o_C1)-[:RELATED_TO*]-(o_C2)\n";
        assertEquals(expected, cypher);
    }
}
