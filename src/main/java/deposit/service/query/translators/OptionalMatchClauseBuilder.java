package deposit.service.query.translators;

import deposit.service.query.elements.ParsedQuery;
import deposit.service.query.elements.WhereCondition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class OptionalMatchClauseBuilder {

	public static String buildOptionalMatchClause(ParsedQuery parsedQuery, Set<String> objectVariables, Set<String> relVariables) {
		List<String> optionalMatchPatterns = new ArrayList<>();

		processExplicitRelationships(parsedQuery, optionalMatchPatterns, relVariables);

		List<String[]> relationshipPairs = getRelationshipPairs(parsedQuery, objectVariables);
		processUnspecifiedRelationships(relationshipPairs, optionalMatchPatterns);

		return assembleOptionalMatchClause(optionalMatchPatterns);
	}
	
	private static void processExplicitRelationships(ParsedQuery parsedQuery, List<String> optionalMatchPatterns, Set<String> relVariables) {
		for (WhereCondition condition : parsedQuery.getRelationshipConditions()) {
			String relationshipMatch = buildRelationshipMatch(condition, true);
			relVariables.add(condition.getRelVariable());
			optionalMatchPatterns.add(relationshipMatch);
		}
	}
	
	private static void processUnspecifiedRelationships(List<String[]> relationshipPairs, List<String> optionalMatchPatterns) {
		for (String[] pair : relationshipPairs) {
			String relationshipMatch = buildUnspecifiedRelationshipMatch(pair[0], pair[1]);
			optionalMatchPatterns.add(relationshipMatch);
		}
	}
	
	private static String assembleOptionalMatchClause(List<String> optionalMatchPatterns) {
		if (!optionalMatchPatterns.isEmpty()) {
			return "OPTIONAL MATCH " + String.join(", ", optionalMatchPatterns);
		}
		return "";
	}

	private static List<String[]> getRelationshipPairs(ParsedQuery parsedQuery, Set<String> objectVariables) {
		List<String[]> relationshipPairs = new ArrayList<>();
		Set<String> definedRelationships = collectDefinedRelationships(parsedQuery);

		if (parsedQuery.getSelectItems().isEmpty()) {
			return relationshipPairs;
		}

		String mainObjectVar = parsedQuery.getSelectItems().get(0).getObjectVariable();
		for (String relatedObjectVar : objectVariables) {
			if (!relatedObjectVar.equals(mainObjectVar)) {
				String relationshipKey = mainObjectVar + "-" + relatedObjectVar;
				if (!definedRelationships.contains(relationshipKey)) {
					relationshipPairs.add(new String[]{mainObjectVar, relatedObjectVar});
				}
			}
		}

		return relationshipPairs;
	}

	private static Set<String> collectDefinedRelationships(ParsedQuery parsedQuery) {
		Set<String> definedRelationships = new HashSet<>();
		for (WhereCondition condition : parsedQuery.getRelationshipConditions()) {
			String relationshipKey = condition.getLeftOperand() + "-" + condition.getRightOperand();
			definedRelationships.add(relationshipKey);
		}
		return definedRelationships;
	}

	private static String buildRelationshipMatch(WhereCondition condition, boolean isExplicit) {
		String relationshipPart = buildRelationshipPart(condition, isExplicit);
		String direction = getCypherDirection(condition.getRelationshipType());

		return formatRelationshipMatch(condition, relationshipPart, direction);
	}

	private static String buildRelationshipPart(WhereCondition condition, boolean isExplicit) {
		String relVariable = condition.getRelVariable();
		String hops = buildHops(condition);

		if (isExplicit && !condition.getLabel().isEmpty()) {
			return String.format("%s:RELATED_TO%s {label: '%s'}", relVariable, hops, condition.getLabel());
		}
		return String.format("%s:RELATED_TO%s", relVariable, hops);
	}

	private static String formatRelationshipMatch(WhereCondition condition, String relationshipPart, String direction) {
		if (direction.equals("<-")) {
			return String.format("(%s)<-[%s]-(%s)", condition.getRightOperand(), relationshipPart, condition.getLeftOperand());
		} else if (direction.equals("->")) {
			return String.format("(%s)-[%s]->(%s)", condition.getLeftOperand(), relationshipPart, condition.getRightOperand());
		} else {
			return String.format("(%s)-[%s]-(%s)", condition.getLeftOperand(), relationshipPart, condition.getRightOperand());
		}
	}

	private static String buildHops(WhereCondition condition) {
		String minHops = condition.getMinHops() != null ? condition.getMinHops() : "";
		String maxHops = condition.getMaxHops() != null ? condition.getMaxHops() : "";

		if (!minHops.isEmpty() || !maxHops.isEmpty()) {
			return String.format("*%s..%s", minHops, maxHops);
		}

		if (condition.hasAsterisk()) {
			return "*";
		}

		return "";
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

	private static String buildUnspecifiedRelationshipMatch(String leftVar, String rightVar) {
		return "(%s)-[:RELATED_TO*]-(%s)".formatted(leftVar, rightVar);
	}
}
