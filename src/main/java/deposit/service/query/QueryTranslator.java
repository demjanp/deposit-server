package deposit.service.query;

import deposit.service.query.elements.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class QueryTranslator {

    public static String translateToCypher(ParsedQuery parsedQuery) {
        StringBuilder cypherQuery = new StringBuilder();

        // Step 1: Build MATCH clause
        String matchClause = buildMatchClause(parsedQuery);
        boolean hasMatch = !matchClause.isEmpty();

        // Step 2: Build OPTIONAL MATCH clause
        String optionalMatchClause = buildOptionalMatchClause(parsedQuery);
        boolean hasOptionalMatch = !optionalMatchClause.isEmpty();

        // Step 3: Append the MATCH clause first
        if (hasMatch) {
            cypherQuery.append(matchClause).append("\n");
        }

        // Step 4: Prepare variables for the WITH clause
        List<String> variables = new ArrayList<>();
        for (SelectItem selectItem : parsedQuery.getSelectItems()) {
            variables.add(selectItem.getObjectVariable());
        }

        // Step 5: Add WITH clause if a MATCH or OPTIONAL MATCH will follow
        if (!variables.isEmpty() && hasOptionalMatch) {
            // Add the WITH clause before the OPTIONAL MATCH clause
            cypherQuery.append("WITH ").append(String.join(", ", variables)).append("\n");
        }

        // Step 6: Append the OPTIONAL MATCH clause
        if (hasOptionalMatch) {
            cypherQuery.append(optionalMatchClause).append("\n");
        }

        return cypherQuery.toString();
    }

    static String buildMatchClause(ParsedQuery parsedQuery) {
        StringBuilder matchClause = new StringBuilder("MATCH ");

        List<String> matchPatterns = new ArrayList<>();

        // Add MATCH clauses for objects
        matchPatterns.addAll(buildObjectMatchPatterns(parsedQuery.getSelectItems()));

        // Add MATCH clauses for explicitly defined relationships
        for (RelationshipCondition condition : parsedQuery.getRelationshipConditions()) {
            matchPatterns.add(buildRelationshipMatch(condition));
        }

        // Join all match patterns with commas
        matchClause.append(String.join(", ", matchPatterns));
        return matchClause.toString();
    }

    static String buildOptionalMatchClause(ParsedQuery parsedQuery) {
        StringBuilder optionalMatchClause = new StringBuilder("OPTIONAL MATCH ");
        List<String[]> undefinedPairs = getUndefinedRelationshipPairs(parsedQuery.getSelectItems(), parsedQuery.getRelationshipConditions());

        if (undefinedPairs.isEmpty()) {
            return "";  // No optional match if all relationships are explicitly defined
        }

        // Generate OPTIONAL MATCH clauses for undefined relationships
        for (String[] pair : undefinedPairs) {
            String optionalMatch = "(%s)-[:RELATED_TO*]-(%s)".formatted(pair[0], pair[1]);
            optionalMatchClause.append(optionalMatch).append(", ");
        }

        // Remove the trailing comma
        if (optionalMatchClause.length() > 0) {
            optionalMatchClause.setLength(optionalMatchClause.length() - 2);  // Remove trailing comma and space
        }

        return optionalMatchClause.toString();
    }

    private static List<String[]> getUndefinedRelationshipPairs(List<SelectItem> selectItems, List<RelationshipCondition> relationshipConditions) {
        List<String[]> undefinedPairs = new ArrayList<>();
        Set<String> definedRelationships = new HashSet<>();

        // Collect explicitly defined relationships
        for (RelationshipCondition condition : relationshipConditions) {
            definedRelationships.add(condition.getLeftVariable() + "-" + condition.getRightVariable());
            definedRelationships.add(condition.getRightVariable() + "-" + condition.getLeftVariable());  // Add both directions
        }

        // The first item in the SELECT clause is the main node
        if (selectItems.isEmpty()) {
            return undefinedPairs;  // No relationships if no select items
        }

        String mainObjectVar = selectItems.get(0).getObjectVariable();

        // Generate all pairs of selected objects related to the main object and check if they have an undefined relationship
        for (int i = 1; i < selectItems.size(); i++) {
            String relatedObjectVar = selectItems.get(i).getObjectVariable();
            String pairForward = mainObjectVar + "-" + relatedObjectVar;
            String pairReverse = relatedObjectVar + "-" + mainObjectVar;

            // Only add pairs if the relationship is not explicitly defined
            if (!definedRelationships.contains(pairForward) && !definedRelationships.contains(pairReverse)) {
                undefinedPairs.add(new String[]{mainObjectVar, relatedObjectVar});
            }
        }

        return undefinedPairs;
    }

    private static String buildRelationshipMatch(RelationshipCondition condition) {
        String leftVar = condition.getLeftVariable();
        String rightVar = condition.getRightVariable();
        String label = condition.getLabel();  // Use the label directly, or leave empty if none provided
        String direction = getCypherDirection(condition.getRelationshipType());

        String hops = "";
        if (condition.getMinHops() != null || condition.getMaxHops() != null) {
            hops = "*" + (condition.getMinHops() != null ? condition.getMinHops() : "") +
                    ".." + (condition.getMaxHops() != null ? condition.getMaxHops() : "");
        }

        // If no label is provided, omit the label part from the relationship pattern
        String relationshipPart = label.isEmpty() ? hops : ":%s%s".formatted(label, hops);

        // Format relationship according to direction
        if (direction.equals("<-")) {
            return "(%s)<-[%s]-(%s)".formatted(rightVar, relationshipPart, leftVar);  // Invert variables for incoming
        } else if (direction.equals("->")) {
            return "(%s)-[%s]->(%s)".formatted(leftVar, relationshipPart, rightVar);  // Outgoing
        } else {
            return "(%s)-[%s]-(%s)".formatted(leftVar, relationshipPart, rightVar);  // Bidirectional
        }
    }

    private static String getCypherDirection(String relationshipType) {
        switch (relationshipType) {
            case "INCOMING":
                return "<-";
            case "OUTGOING":
                return "->";
            default:
                return "--";
        }
    }


    private static List<String> buildObjectMatchPatterns(List<SelectItem> selectItems) {
        List<String> matchPatterns = new ArrayList<>();
        for (SelectItem selectItem : selectItems) {
            matchPatterns.add(generateMatchForClass(selectItem, selectItem.getClassName()));
            if (selectItem.getDescriptor() != null) {
                matchPatterns.add(generateMatchForDescriptor(selectItem));
            }
        }
        return matchPatterns;
    }

    private static String generateMatchForClass(SelectItem selectItem, String className) {
        String objectVariable = selectItem != null ? selectItem.getObjectVariable() : "o_" + QueryUtils.sanitizeAlias(className);

        return "(:Namespace {name: $namespaceName})-[:CONTAINS]->(" + objectVariable + ":Object)" +
               "-[:MEMBER_OF]->(:Class {name: '" + className + "'})";
    }

    private static String generateMatchForDescriptor(SelectItem selectItem) {
        String objectVariable = selectItem.getObjectVariable();
        String descriptorVariable = selectItem.getVariable();

        return "(" + objectVariable + ")-[:HAS_DESCRIPTOR {name: '" + selectItem.getDescriptor() + "'}]-(" + descriptorVariable + ":Descriptor)";
    }
}
