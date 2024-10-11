package deposit.service.query.translators;

import deposit.service.query.elements.CompositeCondition;
import deposit.service.query.elements.ParsedQuery;
import deposit.service.query.elements.SelectItem;
import deposit.service.query.elements.WhereCondition;

import java.util.LinkedHashSet;
import java.util.Set;

public class MatchClauseBuilder {

	public static String buildMatchClause(ParsedQuery parsedQuery, Set<String> objectVariables, Set<String> descriptorVariables, Set<String> relVariables) {

		Set<SelectItem> queryItems = new LinkedHashSet<>();
		Set<WhereCondition> definedRelationships = new LinkedHashSet<>();
		Set<WhereCondition> undefinedRelationships = new LinkedHashSet<>();

		SelectItem firstSelectItem = parsedQuery.getSelectItems().get(0);

		// Collect all SelectItems and Relationships from the SELECT and WHERE clauses
		for (SelectItem selectItem : parsedQuery.getSelectItems()) {
			queryItems.add(selectItem);
		}
		for (WhereCondition condition : parsedQuery.getWhereConditions()) {
			collectFromWhereCondition(condition, parsedQuery, queryItems, definedRelationships);
		}

		// For each defined relationship none of the operands is the firstSelectItem, create an undefined relationship between the first operand and firstSelectItem
		String firstObjectVariable = firstSelectItem.getObjectVariable();
		String firstClassName = firstSelectItem.getClassName();
		String firstAlias = firstSelectItem.getAlias();
		Set<String> variablesWithRelationships = new LinkedHashSet<>();
		variablesWithRelationships.add(firstObjectVariable);
		for (WhereCondition condition : definedRelationships) {
			variablesWithRelationships.add(condition.getLeftOperand());
			variablesWithRelationships.add(condition.getRightOperand());
			if (!condition.getLeftOperand().equals(firstObjectVariable) && !condition.getRightOperand().equals(firstObjectVariable)) {
				WhereCondition relationship = new WhereCondition(firstObjectVariable, "--", condition.getLeftOperand(), firstClassName, condition.getLeftClass(),
					null, null, firstAlias, condition.getLeftAlias(), "", "BIDIRECTIONAL", null, null, true);
				undefinedRelationships.add(relationship);
			}
		}

		// For each SelectItem which is not in a defined or undefined relationship, create an undefined relationship to firstSelectItem
		String objectVariable;
		for (SelectItem selectItem : queryItems) {
			if (selectItem != firstSelectItem) {
				objectVariable = selectItem.getObjectVariable();
				if (!variablesWithRelationships.contains(objectVariable)) {
					WhereCondition relationship = new WhereCondition(firstObjectVariable, "--", objectVariable, firstClassName, selectItem.getClassName(),
						null, null, firstAlias, selectItem.getAlias(), "", "BIDIRECTIONAL", null, null, true);
					undefinedRelationships.add(relationship);
				}
			}
		}

		// Generate the first MATCH clause
		StringBuilder matchClause = new StringBuilder("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->");
		matchClause.append(generateMatchForClass(firstObjectVariable, firstClassName));
		objectVariables.add(firstObjectVariable);
		generateMatchesForDescriptors(matchClause, firstObjectVariable, queryItems, descriptorVariables);
		matchClause.append("\n");

		// Generate OPTIONAL MATCH clauses for each SelectItem
		String className;
		for (WhereCondition condition : undefinedRelationships) {
			objectVariable = condition.getRightOperand();
			className = condition.getRightClass();
			matchClause.append(generateUndefinedRelationshipMatch(firstObjectVariable, objectVariable, className));
			objectVariables.add(objectVariable);
			generateMatchesForDescriptors(matchClause, objectVariable, queryItems, descriptorVariables);
			matchClause.append("\n");
		}
		String leftVar;
		String rightVar;
		boolean reversed;
		for (WhereCondition condition : definedRelationships) {
			reversed = objectVariables.contains(condition.getRightOperand());
			matchClause.append(generateDefinedRelationshipMatch(condition, reversed, relVariables));
			objectVariable = reversed ? condition.getLeftOperand() : condition.getRightOperand();
			objectVariables.add(objectVariable);
			generateMatchesForDescriptors(matchClause, objectVariable, queryItems, descriptorVariables);
			matchClause.append("\n");
		}

		return matchClause.toString();
	}

	private static void generateMatchesForDescriptors(StringBuilder matchClause, String objectVariable, Set<SelectItem> queryItems, Set<String> descriptorVariables) {
		for (SelectItem selectItem : queryItems) {
			if ((selectItem.getDescriptor() != null) && (selectItem.getObjectVariable().equals(objectVariable))) {
				matchClause.append("\n");
				matchClause.append(generateMatchForDescriptor(objectVariable, selectItem.getVariable(), selectItem.getDescriptor()));
				descriptorVariables.add(selectItem.getVariable());
			}
		}
	}

	private static void collectFromWhereCondition(WhereCondition condition, ParsedQuery parsedQuery, Set<SelectItem> queryItems, Set<WhereCondition> definedRelationships) {
		if (condition instanceof CompositeCondition) {
			collectFromCompositeCondition((CompositeCondition) condition, parsedQuery, queryItems, definedRelationships);
		} else if (condition.getRelationshipType() == null) {
			collectFromConditionSide(condition.getLeftClass(), condition.getLeftDescriptor(), condition.getLeftAlias(), parsedQuery, queryItems);
			collectFromConditionSide(condition.getRightClass(), condition.getRightDescriptor(), condition.getRightAlias(), parsedQuery, queryItems);
		} else {
			definedRelationships.add(condition);
		}
	}

	private static void collectFromCompositeCondition(CompositeCondition compositeCondition, ParsedQuery parsedQuery, Set<SelectItem> queryItems, Set<WhereCondition> definedRelationships) {
		for (WhereCondition subCondition : compositeCondition.getConditions()) {
			collectFromWhereCondition(subCondition, parsedQuery, queryItems, definedRelationships);
		}
	}

	private static void collectFromConditionSide(String className, String descriptorName, String alias, ParsedQuery parsedQuery, Set<SelectItem> queryItems) {
		if (parsedQuery.getSelectItemByAlias(alias) != null) {
			return;
		}
		if (className != null) {
			SelectItem selectItem = new SelectItem(className, descriptorName, "");
			queryItems.add(selectItem);
		}
	}	
	
	public static String generateMatchForClass(String objectVariable, String className) {
		return "(" + objectVariable + ":Object)-[:MEMBER_OF]->(:Class {name: '" + className + "'})";
	}

	public static String generateMatchForDescriptor(String objectVariable, String descriptorVariable, String descriptor) {
		return "OPTIONAL MATCH (%s)-[:HAS_DESCRIPTOR]-(%s:Descriptor {name: '%s'})".formatted(objectVariable, descriptorVariable, descriptor);
	}

	private static String generateUndefinedRelationshipMatch(String leftVar, String rightVar, String rightClass) {
		return "OPTIONAL MATCH (%s)-[:RELATED_TO*]-%s".formatted(leftVar, generateMatchForClass(rightVar, rightClass));
	}

	private static String generateDefinedRelationshipMatch(WhereCondition condition, boolean reversed, Set<String> relVariables) {
		String leftVar = reversed ? condition.getRightOperand() : condition.getLeftOperand();
		String rightVar = reversed ? condition.getLeftOperand() : condition.getRightOperand();
		String className = reversed ? condition.getLeftClass() : condition.getRightClass();
		String leftOperator;
		String rightOperator;
		switch (condition.getRelationshipType()) {
			case "INCOMING":
				leftOperator = !reversed ? "<-" : "-";
				rightOperator = !reversed ? "-" : "->";
				break;
			case "OUTGOING":
				leftOperator = !reversed ? "-" : "<-";
				rightOperator = !reversed ? "->" : "-";
				break;
			default:
				leftOperator = "-";
				rightOperator = "-";
				break;
		}
		relVariables.add(condition.getRelVariable());
		return String.format("OPTIONAL MATCH (%s)%s[%s]%s%s",
			leftVar,
			leftOperator,
			generateRelationshipMiddle(condition),
			rightOperator,
			generateMatchForClass(rightVar, className)
		);
	}

	private static String generateRelationshipMiddle(WhereCondition condition) {
		String relVariable = condition.getRelVariable();
		String hops = buildHops(condition);
		String label = condition.getLabel();
		if (label.isEmpty()) {
			return String.format("%s:RELATED_TO%s", relVariable, hops);
		} else {
			return String.format("%s:RELATED_TO%s {label: '%s'}", relVariable, hops, label);
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
}
