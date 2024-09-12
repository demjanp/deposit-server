package deposit.service.query;

import deposit.service.query.elements.*;

import java.text.DecimalFormatSymbols;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class QueryTranslator {
	
	private static DecimalFormat decimalFormat;
	
    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setDecimalSeparator('.');  // Force dot (.) as the decimal separator
        decimalFormat = new DecimalFormat();
        decimalFormat.setDecimalFormatSymbols(symbols);
    }
    
	public static String translateToCypher(String query) {
		ParsedQuery parsedQuery = QueryParser.parse(query);
		StringBuilder cypherQuery = new StringBuilder();
		Set<String> objectVariables = new HashSet<>();
		Set<String> descriptorVariables = new HashSet<>();
		Set<String> relVariables = new HashSet<>();
		
		String matchClause = buildMatchClause(parsedQuery, objectVariables, descriptorVariables);
		String optionalMatchClause = buildOptionalMatchClause(parsedQuery, objectVariables, relVariables);
		Set<String> matchedVariables = new HashSet<>(objectVariables);
		matchedVariables.addAll(descriptorVariables);
		
		String whereClause = buildWhereClause(parsedQuery);
		
		String returnClause = buildReturnClause(parsedQuery);
		
		if (!matchClause.isEmpty()) {
			cypherQuery.append(matchClause).append("\n");
		}

		if (!matchedVariables.isEmpty() && !optionalMatchClause.isEmpty()) {
			cypherQuery.append("WITH ").append(String.join(", ", matchedVariables)).append("\n");
		}

		if (!optionalMatchClause.isEmpty()) {
			cypherQuery.append(optionalMatchClause).append("\n");
		}

		matchedVariables.addAll(relVariables);
		if (!matchedVariables.isEmpty()) {
			cypherQuery.append("WITH ").append(String.join(", ", matchedVariables)).append("\n");
		}

		if (!whereClause.isEmpty()) {
			cypherQuery.append("WHERE ").append(whereClause).append("\n");
		}
		
		// Add the RETURN clause
		if (!returnClause.isEmpty()) {
            cypherQuery.append("RETURN ").append(returnClause);
        }

		return cypherQuery.toString();
	}
	
	static String buildMatchClause(ParsedQuery parsedQuery, Set<String> objectVariables, Set<String> descriptorVariables) {
		StringBuilder matchClause = new StringBuilder("MATCH ");
		List<String> matchPatterns = new ArrayList<>();
		Set<String> matchedClasses = new HashSet<>();
		Set<String> matchedDescriptors = new HashSet<>();

		// Process SelectItems for matching classes and descriptors
		for (SelectItem selectItem : parsedQuery.getSelectItems()) {
			objectVariables.add(selectItem.getObjectVariable());
			matchPatterns.add(generateMatchForClassFromSelectItem(selectItem));
			matchedClasses.add(selectItem.getClassName());
			if (selectItem.getDescriptor() != null) {
				matchPatterns.add(generateMatchForDescriptorFromSelectItem(selectItem));
				matchedDescriptors.add(selectItem.getClassName() + "." + selectItem.getDescriptor());
				descriptorVariables.add(selectItem.getVariable());
			}
		}

		// Process WhereConditions for additional matches
		for (WhereCondition condition : parsedQuery.getWhereConditions()) {
			handleWhereCondition(condition, parsedQuery, matchPatterns, matchedClasses, matchedDescriptors, objectVariables, descriptorVariables);
		}

		matchClause.append(String.join(", ", matchPatterns));
		return matchClause.toString();
	}
	
	static String buildOptionalMatchClause(ParsedQuery parsedQuery, Set<String> objectVariables, Set<String> relVariables) {
		List<String> optionalMatchPatterns = new ArrayList<>();
		List<String[]> relationshipPairs = getRelationshipPairs(parsedQuery, objectVariables);

		// Add explicit relationship matches
		for (WhereCondition condition : parsedQuery.getRelationshipConditions()) {
			String relationshipMatch = buildRelationshipMatch(condition, true);
			relVariables.add(condition.getRelVariable());
			optionalMatchPatterns.add(relationshipMatch);
		}

		// Add unspecified relationship matches
		for (String[] pair : relationshipPairs) {
			String relationshipMatch = buildUnspecifiedRelationshipMatch(pair[0], pair[1]);
			optionalMatchPatterns.add(relationshipMatch);
		}

		if (!optionalMatchPatterns.isEmpty()) {
			return "OPTIONAL MATCH " + String.join(", ", optionalMatchPatterns);
		}
		return "";
	}
	
	static String buildWhereClause(ParsedQuery parsedQuery) {
		List<String> conditions = new ArrayList<>();

		for (WhereCondition condition : parsedQuery.getWhereConditions()) {
			addConditionAndDescriptors(condition, parsedQuery, conditions);
		}

		String conditionsString = String.join(" ", conditions);

		return conditionsString;
	}
	
	static String buildReturnClause(ParsedQuery parsedQuery) {
		StringBuilder returnClause = new StringBuilder();

		if (parsedQuery.isDistinct()) {
			returnClause.append("DISTINCT ");
		}
		
		List<SelectItem> selectItems = parsedQuery.getSelectItems();
		for (int i = 0; i < selectItems.size(); i++) {
			SelectItem selectItem = selectItems.get(i);

			if (i > 0) {
				returnClause.append(", ");
			}
			
			if (selectItem.isAggregation()) {
				appendAggregationFunction(returnClause, selectItem);
			} else {
				appendItem(returnClause, selectItem);
			}
		}

		return returnClause.toString();
	}
	
	
	// buildMatchClause helpers
	private static void handleWhereCondition(WhereCondition condition, ParsedQuery parsedQuery, List<String> matchPatterns, Set<String> matchedClasses, Set<String> matchedDescriptors, Set<String> objectVariables, Set<String> descriptorVariables) {
		if (condition instanceof CompositeCondition) {
			CompositeCondition compositeCondition = (CompositeCondition) condition;
			for (WhereCondition subCondition : compositeCondition.getConditions()) {
				handleWhereCondition(subCondition, parsedQuery, matchPatterns, matchedClasses, matchedDescriptors, objectVariables, descriptorVariables);
			}
		} else if (condition.getRelationshipType() == null) {
			// Reuse the logic for both left and right sides
			processConditionSide(condition.getLeftClass(), condition.getLeftDescriptor(), condition.getLeftAlias(), matchPatterns, matchedClasses, matchedDescriptors, parsedQuery, objectVariables, descriptorVariables);
			processConditionSide(condition.getRightClass(), condition.getRightDescriptor(), condition.getRightAlias(), matchPatterns, matchedClasses, matchedDescriptors, parsedQuery, objectVariables, descriptorVariables);
		}
	}
	
	private static void processConditionSide(String className, String descriptorName, String alias, List<String> matchPatterns, Set<String> matchedClasses, Set<String> matchedDescriptors, ParsedQuery parsedQuery, Set<String> objectVariables, Set<String> descriptorVariables) {

		if (parsedQuery.getSelectItemByAlias(alias) != null) {
			// Skip if already processed in SELECT clause
			return;
		}

		// Handle class matching
		if (className != null && !matchedClasses.contains(className)) {
			matchPatterns.add(generateMatchForClassFromCondition(className, parsedQuery, objectVariables));
			matchedClasses.add(className);
		}

		// Handle descriptor matching
		if (descriptorName != null) {
			String descriptorKey = className + "." + descriptorName;
			if (!matchedDescriptors.contains(descriptorKey)) {
				matchPatterns.add(generateMatchForDescriptorFromCondition(className, descriptorName, objectVariables, descriptorVariables));
				matchedDescriptors.add(descriptorKey);
			}
		}
	}
	
	
	// buildOptionalMatchClause helpers
	private static List<String[]> getRelationshipPairs(ParsedQuery parsedQuery, Set<String> objectVariables) {
		List<SelectItem> selectItems = parsedQuery.getSelectItems();
		List<WhereCondition> relationshipConditions = parsedQuery.getRelationshipConditions();

		List<String[]> relationshipPairs = new ArrayList<>();
		Set<String> definedRelationships = new HashSet<>();

		// Collect explicitly defined relationships
		for (WhereCondition condition : relationshipConditions) {
			String pairForward = condition.getLeftOperand() + "-" + condition.getRightOperand();
			definedRelationships.add(pairForward);
		}

		if (selectItems.isEmpty()) {
			return relationshipPairs;
		}

		String mainObjectVar = selectItems.get(0).getObjectVariable();
		for (String relatedObjectVar : objectVariables) {
			if (!relatedObjectVar.equals(mainObjectVar)) {
				String pairForward = mainObjectVar + "-" + relatedObjectVar;
				if (!definedRelationships.contains(pairForward)) {
					relationshipPairs.add(new String[]{mainObjectVar, relatedObjectVar});
				}
			}
		}
		
		return relationshipPairs;
	}
	
	private static String buildRelationshipMatch(WhereCondition condition, boolean isExplicit) {
		String relationshipPart = buildRelationshipPart(condition, isExplicit);
		String direction = getCypherDirection(condition.getRelationshipType());

		if (direction.equals("<-")) {
			return "(%s)<-[%s]-(%s)".formatted(condition.getRightOperand(), relationshipPart, condition.getLeftOperand());
		} else if (direction.equals("->")) {
			return "(%s)-[%s]->(%s)".formatted(condition.getLeftOperand(), relationshipPart, condition.getRightOperand());
		} else {
			return "(%s)-[%s]-(%s)".formatted(condition.getLeftOperand(), relationshipPart, condition.getRightOperand());
		}
	}
	
	private static String buildRelationshipPart(WhereCondition condition, boolean isExplicit) {
		String relVariable = condition.getRelVariable();
		String hops = condition.getMinHops() != null || condition.getMaxHops() != null
				? "*" + (condition.getMinHops() != null ? condition.getMinHops() : "") + ".." + (condition.getMaxHops() != null ? condition.getMaxHops() : "")
				: "";
		if (hops.isEmpty() && condition.hasAsterisk()) {
			hops = "*";
		}

		if (isExplicit && !condition.getLabel().isEmpty()) {
			return "%s:RELATED_TO%s {label: '%s'}".formatted(relVariable, hops, condition.getLabel());
		}
		return "%s:RELATED_TO%s".formatted(relVariable, hops);  // No label for undefined relationships
	}
	
	private static String buildUnspecifiedRelationshipMatch(String leftVar, String rightVar) {
		return "(%s)-[:RELATED_TO*]-(%s)".formatted(leftVar, rightVar);
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
		
	
	// buildWhereClause helpers
	private static void addConditionAndDescriptors(WhereCondition condition, ParsedQuery parsedQuery, List<String> conditions) {
		if (condition instanceof CompositeCondition) {
			CompositeCondition compositeCondition = (CompositeCondition) condition;
			StringBuilder compositeClause = new StringBuilder(condition.isNegated() ? "NOT (" : "(");
			List<WhereCondition> subConditions = compositeCondition.getConditions();
			List<String> logicalOperators = compositeCondition.getLogicalOperators();
			for (int i = 0; i < subConditions.size(); i++) {
				List<String> subConditionClause = new ArrayList<>();
				addConditionAndDescriptors(subConditions.get(i), parsedQuery, subConditionClause);
				compositeClause.append(subConditionClause.get(subConditionClause.size() - 1));
				if (i < logicalOperators.size()) {
					compositeClause.append(" ").append(logicalOperators.get(i)).append(" ");
				}
			}
			compositeClause.append(")");
			conditions.add(compositeClause.toString());
		} else {
			if (condition.getRelationshipType() != null) {
				conditions.add(condition.getRelVariable() + " IS NOT NULL");
			} else {
				String leftOperand = replaceWithVariable(condition.getLeftOperand(), parsedQuery);
				String rightOperand = replaceWithVariable(condition.getRightOperand(), parsedQuery);
				String clause = leftOperand + " " + condition.getOperator() + " " + rightOperand;
				if (condition.isNegated()) {
					clause = "NOT (" + clause + ")";
				}
				conditions.add(clause);
			}
		}
	}
	
	private static String replaceWithVariable(String operand, ParsedQuery parsedQuery) {
		SelectItem selectItem = parsedQuery.getSelectItemByAlias(operand);

		if (selectItem != null) {
			// If the operand refers to an object, use its object variable
			if (selectItem.getDescriptor() == null) {
				return selectItem.getObjectVariable();
			}
			// If the operand refers to a descriptor, use its descriptor variable
			return selectItem.getVariable();
		}

		// If the operand is not found in the select items, return it as-is (likely a literal value)
		return operand;
	}
	
	
	// buildReturnClause helpers
	private static void appendAggregationFunction(StringBuilder returnClause, SelectItem selectItem) {
		String function = selectItem.getAggregationFunction().toUpperCase();
		String distinct = selectItem.isDistinct() ? "DISTINCT " : "";
		if ("PERCENTILE".equalsIgnoreCase(function) && selectItem.getFunctionValue() != null) {
			String formattedPercentile = decimalFormat.format(selectItem.getFunctionValue());
			returnClause.append(String.format("percentileCont(%s%s.value, %s) AS %s", distinct, selectItem.getVariable(), formattedPercentile, selectItem.getAlias()));
		} else if ("COUNT".equalsIgnoreCase(function)) {
			returnClause.append(String.format("%s(%s%s) AS %s", function, distinct, selectItem.getObjectVariable(), selectItem.getAlias()));
		} else {
			returnClause.append(String.format("%s(%s%s.value) AS %s", function, distinct, selectItem.getVariable(), selectItem.getAlias()));
		}
	}
	
	private static void appendItem(StringBuilder returnClause, SelectItem selectItem) {
		String variable;
		if (selectItem.getDescriptor() != null && !selectItem.getDescriptor().isEmpty()) {
			variable = selectItem.getVariable();
		} else {
			variable = selectItem.getObjectVariable();
		}
		returnClause.append(String.format("%s AS %s", variable, selectItem.getAlias()));
	}
	
	
	// Class and Descriptor MATCH clause generators
	private static String generateMatchForClass(String objectVariable, String className) {
		return "(:Namespace {name: $namespaceName})-[:CONTAINS]->(" + objectVariable + ":Object)" +
				"-[:MEMBER_OF]->(:Class {name: '" + className + "'})";
	}
	
	private static String generateMatchForDescriptor(String objectVariable, String descriptorVariable, String descriptor) {
		return "(" + objectVariable + ")-[:HAS_DESCRIPTOR]-(" + descriptorVariable + ":Descriptor {name: '" + descriptor + "'})";
	}
	
	private static String generateMatchForClassFromSelectItem(SelectItem selectItem) {
		return generateMatchForClass(selectItem.getObjectVariable(), selectItem.getClassName());
	}
	
	private static String generateMatchForDescriptorFromSelectItem(SelectItem selectItem) {
		return generateMatchForDescriptor(selectItem.getObjectVariable(), selectItem.getVariable(), selectItem.getDescriptor());
	}
	
	private static String generateMatchForClassFromCondition(String className, ParsedQuery parsedQuery, Set<String> objectVariables) {
		String objectVariable = "o_" + QueryUtils.generateAliasForClass(className);
		objectVariables.add(objectVariable);
		return generateMatchForClass(objectVariable, className);
	}
	
	private static String generateMatchForDescriptorFromCondition(String className, String descriptor, Set<String> objectVariables, Set<String> descriptorVariables) {
		String objectVariable = "o_" + QueryUtils.generateAliasForClass(className);
		String descriptorVariable = "d_" + QueryUtils.generateAliasForDescriptor(className, descriptor);
		objectVariables.add(objectVariable);
		descriptorVariables.add(descriptorVariable);
		return generateMatchForDescriptor(objectVariable, descriptorVariable, descriptor);
	}
}
