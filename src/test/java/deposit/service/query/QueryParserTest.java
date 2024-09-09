package deposit.service.query;

import deposit.service.query.elements.ParsedQuery;
import deposit.service.query.elements.SelectItem;
import deposit.service.query.elements.WhereCondition;
import deposit.service.query.elements.RelationshipCondition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class QueryParserTest {

    @Test
    public void testSimpleSelect() {
        String query = "SELECT C1";
        ParsedQuery parsedQuery = QueryParser.parse(query);

        // Verify the number of select items
        assertEquals(1, parsedQuery.getSelectItems().size());

        // Check that the correct class is parsed
        SelectItem selectItem = parsedQuery.getSelectItems().get(0);
        assertEquals("C1", selectItem.getClassName());
        assertNull(selectItem.getDescriptor());
        assertEquals("C1", selectItem.getAlias());
        assertNull(selectItem.getAggregationFunction());
    }

    @Test
    public void testSelectWithDescriptorAndAlias() {
        String query = "SELECT C1.D1 AS Alias1";
        ParsedQuery parsedQuery = QueryParser.parse(query);

        // Verify the number of select items
        assertEquals(1, parsedQuery.getSelectItems().size());

        // Check that the class, descriptor, and alias are correctly parsed
        SelectItem selectItem = parsedQuery.getSelectItems().get(0);
        assertEquals("C1", selectItem.getClassName());
        assertEquals("D1", selectItem.getDescriptor());
        assertEquals("Alias1", selectItem.getAlias());
    }

    @Test
    public void testSelectWithAggregationFunction() {
        String query = "SELECT COUNT(C1.D1) AS TotalCount";
        ParsedQuery parsedQuery = QueryParser.parse(query);

        // Verify the number of select items
        assertEquals(1, parsedQuery.getSelectItems().size());

        // Check that the aggregation function, class, and alias are correctly parsed
        SelectItem selectItem = parsedQuery.getSelectItems().get(0);
        assertEquals("C1", selectItem.getClassName());
        assertEquals("D1", selectItem.getDescriptor());
        assertEquals("TotalCount", selectItem.getAlias());
        assertEquals("COUNT", selectItem.getAggregationFunction());
    }

    @Test
    public void testSelectWithMultipleItems() {
        String query = "SELECT C1, C2.D2";
        ParsedQuery parsedQuery = QueryParser.parse(query);

        // Verify the number of select items
        assertEquals(2, parsedQuery.getSelectItems().size());

        // Check the first select item
        SelectItem selectItem1 = parsedQuery.getSelectItems().get(0);
        assertEquals("C1", selectItem1.getClassName());
        assertNull(selectItem1.getDescriptor());
        assertEquals("C1", selectItem1.getAlias());

        // Check the second select item
        SelectItem selectItem2 = parsedQuery.getSelectItems().get(1);
        assertEquals("C2", selectItem2.getClassName());
        assertEquals("D2", selectItem2.getDescriptor());
        assertEquals("C2_D2", selectItem2.getAlias());  // Expect auto-aliasing
    }

    @Test
    public void testWhereClauseSimpleCondition() {
        String query = "SELECT C1 WHERE C1.D1 = 5";
        ParsedQuery parsedQuery = QueryParser.parse(query);

        // Verify the number of select items
        assertEquals(1, parsedQuery.getSelectItems().size());

        // Verify the number of where conditions
        assertEquals(1, parsedQuery.getWhereConditions().size());

        // Check the where condition
        WhereCondition condition = parsedQuery.getWhereConditions().get(0);
        assertEquals("d_C1_D1", condition.getLeftOperand());  // Expect sanitized variable name
        assertEquals("=", condition.getOperator());
        assertEquals("5", condition.getRightOperand());
    }

    @Test
    public void testWhereClauseWithLogicalOperators() {
        String query = "SELECT C1 WHERE C1.D1 = 5 AND C2.D2 > 3";
        ParsedQuery parsedQuery = QueryParser.parse(query);

        // Verify the number of select items
        assertEquals(1, parsedQuery.getSelectItems().size());

        // Verify the number of where conditions
        assertEquals(2, parsedQuery.getWhereConditions().size());

        // Check the first where condition
        WhereCondition condition1 = parsedQuery.getWhereConditions().get(0);
        assertEquals("d_C1_D1", condition1.getLeftOperand());  // Expect sanitized variable name
        assertEquals("=", condition1.getOperator());
        assertEquals("5", condition1.getRightOperand());

        // Check the second where condition
        WhereCondition condition2 = parsedQuery.getWhereConditions().get(1);
        assertEquals("d_C2_D2", condition2.getLeftOperand());  // Expect sanitized variable name
        assertEquals(">", condition2.getOperator());
        assertEquals("3", condition2.getRightOperand());

        // Check that the logical operator between them is "AND"
        assertEquals(1, parsedQuery.getLogicalOperators().size());
        assertEquals("AND", parsedQuery.getLogicalOperators().get(0));
    }

    @Test
    public void testWhereClauseWithOrOperator() {
        String query = "SELECT C1 WHERE C1.D1 = 5 OR C2.D2 < 2";
        ParsedQuery parsedQuery = QueryParser.parse(query);

        // Verify the number of select items
        assertEquals(1, parsedQuery.getSelectItems().size());

        // Verify the number of where conditions
        assertEquals(2, parsedQuery.getWhereConditions().size());

        // Check the first where condition
        WhereCondition condition1 = parsedQuery.getWhereConditions().get(0);
        assertEquals("d_C1_D1", condition1.getLeftOperand());  // Expect sanitized variable name
        assertEquals("=", condition1.getOperator());
        assertEquals("5", condition1.getRightOperand());

        // Check the second where condition
        WhereCondition condition2 = parsedQuery.getWhereConditions().get(1);
        assertEquals("d_C2_D2", condition2.getLeftOperand());  // Expect sanitized variable name
        assertEquals("<", condition2.getOperator());
        assertEquals("2", condition2.getRightOperand());

        // Check that the logical operator between them is "OR"
        assertEquals(1, parsedQuery.getLogicalOperators().size());
        assertEquals("OR", parsedQuery.getLogicalOperators().get(0));
    }

    @Test
    public void testWhereClauseWithRelationship() {
        String query = "SELECT C1, C2 WHERE C1-contains->C2";
        ParsedQuery parsedQuery = QueryParser.parse(query);

        // Verify the number of select items
        assertEquals(2, parsedQuery.getSelectItems().size());

        // Verify the number of where conditions
        assertEquals(0, parsedQuery.getWhereConditions().size());

        // Verify the number of relationship conditions
        assertEquals(1, parsedQuery.getRelationshipConditions().size());

        // Check the relationship condition
        RelationshipCondition relationshipCondition = parsedQuery.getRelationshipConditions().get(0);
        assertEquals("o_C1", relationshipCondition.getLeftVariable());  // Expect sanitized variable name
        assertEquals("contains", relationshipCondition.getLabel());
        assertEquals("o_C2", relationshipCondition.getRightVariable());  // Expect sanitized variable name
        assertEquals("OUTGOING", relationshipCondition.getRelationshipType());
        assertNull(relationshipCondition.getMinHops());
        assertNull(relationshipCondition.getMaxHops());
    }

    @Test
    public void testSelectAndWhereWithAliases() {
        String query = "SELECT C1 AS A, C2 AS B WHERE A-label->B";
        ParsedQuery parsedQuery = QueryParser.parse(query);

        // Verify the number of select items
        assertEquals(2, parsedQuery.getSelectItems().size());

        // Check the first select item (alias A)
        SelectItem selectItem1 = parsedQuery.getSelectItems().get(0);
        assertEquals("C1", selectItem1.getClassName());
        assertNull(selectItem1.getDescriptor());
        assertEquals("A", selectItem1.getAlias());

        // Check the second select item (alias B)
        SelectItem selectItem2 = parsedQuery.getSelectItems().get(1);
        assertEquals("C2", selectItem2.getClassName());
        assertNull(selectItem2.getDescriptor());
        assertEquals("B", selectItem2.getAlias());

        // Verify the where clause condition
        assertEquals(0, parsedQuery.getWhereConditions().size());
        assertEquals(1, parsedQuery.getRelationshipConditions().size());

        // Check the relationship condition
        RelationshipCondition relationshipCondition = parsedQuery.getRelationshipConditions().get(0);
        assertEquals("o_A", relationshipCondition.getLeftVariable());  // Use alias A as o_A
        assertEquals("label", relationshipCondition.getLabel());
        assertEquals("o_B", relationshipCondition.getRightVariable());  // Use alias B as o_B
    }

    @Test
    public void testQuotedClassAndDescriptorNames() {
        // Test query with quoted class and descriptor names
        String query = "SELECT \"Clašš 1\".\"Descriptor 1\" AS Alias1 WHERE Alias1-\"related to\"->\"Člass-2\"";
        ParsedQuery parsedQuery = QueryParser.parse(query);

        // Verify the number of select items
        assertEquals(1, parsedQuery.getSelectItems().size());

        // Check the select item
        SelectItem selectItem = parsedQuery.getSelectItems().get(0);
        assertEquals("Clašš 1", selectItem.getClassName());  // Quotes should be stripped
        assertEquals("Descriptor 1", selectItem.getDescriptor());  // Quotes should be stripped
        assertEquals("Alias1", selectItem.getAlias());

        // Verify the number of relationship conditions
        assertEquals(1, parsedQuery.getRelationshipConditions().size());

        // Check the relationship condition
        RelationshipCondition relationshipCondition = parsedQuery.getRelationshipConditions().get(0);
        assertEquals("o_Alias1", relationshipCondition.getLeftVariable());  // Sanitized variable name
        assertEquals("related to", relationshipCondition.getLabel());  // Quotes should be stripped
        assertEquals("o__lass_2", relationshipCondition.getRightVariable());  // Sanitized variable name
        assertEquals("OUTGOING", relationshipCondition.getRelationshipType());  // Verify direction
    }

}
