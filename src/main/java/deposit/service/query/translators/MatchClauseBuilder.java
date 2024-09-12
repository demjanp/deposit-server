package deposit.service.query.translators;

import deposit.service.query.QueryUtils;
import deposit.service.query.elements.CompositeCondition;
import deposit.service.query.elements.ParsedQuery;
import deposit.service.query.elements.SelectItem;
import deposit.service.query.elements.WhereCondition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MatchClauseBuilder {

	public static String buildMatchClause(ParsedQuery parsedQuery, Set<String> objectVariables, Set<String> descriptorVariables) {
		StringBuilder matchClause = new StringBuilder("MATCH ");
		List<String> matchPatterns = new ArrayList<>();
		Set<String> matchedClasses = new HashSet<>();
		Set<String> matchedDescriptors = new HashSet<>();
		processSelectItems(parsedQuery, matchPatterns, matchedClasses, matchedDescriptors, objectVariables, descriptorVariables);
		processWhereConditions(parsedQuery, matchPatterns, matchedClasses, matchedDescriptors, objectVariables, descriptorVariables);
		matchClause.append(String.join(", ", matchPatterns));
		return matchClause.toString();
	}
	
	private static void processSelectItems(ParsedQuery parsedQuery, List<String> matchPatterns, Set<String> matchedClasses, Set<String> matchedDescriptors, Set<String> objectVariables, Set<String> descriptorVariables) {
		for (SelectItem selectItem : parsedQuery.getSelectItems()) {
			matchPatterns.add(generateMatchForClassFromSelectItem(selectItem));
			matchedClasses.add(selectItem.getClassName());
			objectVariables.add(selectItem.getObjectVariable());

			if (selectItem.getDescriptor() != null) {
				matchPatterns.add(generateMatchForDescriptorFromSelectItem(selectItem));
				matchedDescriptors.add(selectItem.getClassName() + "." + selectItem.getDescriptor());
				descriptorVariables.add(selectItem.getVariable());
			}
		}
	}

	private static void processWhereConditions(ParsedQuery parsedQuery, List<String> matchPatterns, Set<String> matchedClasses, Set<String> matchedDescriptors, Set<String> objectVariables, Set<String> descriptorVariables) {
		for (WhereCondition condition : parsedQuery.getWhereConditions()) {
			handleWhereCondition(condition, parsedQuery, matchPatterns, matchedClasses, matchedDescriptors, objectVariables, descriptorVariables);
		}
	}
	
	private static void handleWhereCondition(WhereCondition condition, ParsedQuery parsedQuery, List<String> matchPatterns,
			Set<String> matchedClasses, Set<String> matchedDescriptors, Set<String> objectVariables, Set<String> descriptorVariables) {

		if (condition instanceof CompositeCondition) {
			processCompositeCondition((CompositeCondition) condition, parsedQuery, matchPatterns, matchedClasses, matchedDescriptors, objectVariables, descriptorVariables);
		} else if (condition.getRelationshipType() == null) {
			processConditionSide(condition.getLeftClass(), condition.getLeftDescriptor(), condition.getLeftAlias(),
								 matchPatterns, matchedClasses, matchedDescriptors, parsedQuery, objectVariables, descriptorVariables);
			processConditionSide(condition.getRightClass(), condition.getRightDescriptor(), condition.getRightAlias(),
								 matchPatterns, matchedClasses, matchedDescriptors, parsedQuery, objectVariables, descriptorVariables);
		}
	}
	
	private static void processCompositeCondition(CompositeCondition compositeCondition, ParsedQuery parsedQuery,
			List<String> matchPatterns, Set<String> matchedClasses, Set<String> matchedDescriptors,
			Set<String> objectVariables, Set<String> descriptorVariables) {

		for (WhereCondition subCondition : compositeCondition.getConditions()) {
			handleWhereCondition(subCondition, parsedQuery, matchPatterns, matchedClasses, matchedDescriptors, objectVariables, descriptorVariables);
		}
	}
	
	private static void processConditionSide(String className, String descriptorName, String alias,
			List<String> matchPatterns, Set<String> matchedClasses, Set<String> matchedDescriptors,
			ParsedQuery parsedQuery, Set<String> objectVariables, Set<String> descriptorVariables) {

		if (parsedQuery.getSelectItemByAlias(alias) != null) {
			return;
		}

		if (className != null && !matchedClasses.contains(className)) {
			matchPatterns.add(generateMatchForClassFromCondition(className, parsedQuery, objectVariables));
			matchedClasses.add(className);
		}

		if (descriptorName != null) {
			String descriptorKey = className + "." + descriptorName;
			if (!matchedDescriptors.contains(descriptorKey)) {
				matchPatterns.add(generateMatchForDescriptorFromCondition(className, descriptorName, objectVariables, descriptorVariables));
				matchedDescriptors.add(descriptorKey);
			}
		}
	}

	private static String generateMatchForClassFromSelectItem(SelectItem selectItem) {
		return generateMatchForClass(selectItem.getObjectVariable(), selectItem.getClassName());
	}
	
	private static String generateMatchForDescriptorFromSelectItem(SelectItem selectItem) {
		return generateMatchForDescriptor(selectItem.getObjectVariable(), selectItem.getVariable(), selectItem.getDescriptor());
	}

	public static String generateMatchForClassFromCondition(String className, ParsedQuery parsedQuery, Set<String> objectVariables) {
		String objectVariable = "o_" + QueryUtils.generateAliasForClass(className);
		objectVariables.add(objectVariable);
		return generateMatchForClass(objectVariable, className);
	}

	public static String generateMatchForDescriptorFromCondition(String className, String descriptor, Set<String> objectVariables, Set<String> descriptorVariables) {
		String objectVariable = "o_" + QueryUtils.generateAliasForClass(className);
		String descriptorVariable = "d_" + QueryUtils.generateAliasForDescriptor(className, descriptor);
		objectVariables.add(objectVariable);
		descriptorVariables.add(descriptorVariable);
		return generateMatchForDescriptor(objectVariable, descriptorVariable, descriptor);
	}

	public static String generateMatchForClass(String objectVariable, String className) {
		return "(:Namespace {name: $namespaceName})-[:CONTAINS]->(" + objectVariable + ":Object)" +
				"-[:MEMBER_OF]->(:Class {name: '" + className + "'})";
	}

	public static String generateMatchForDescriptor(String objectVariable, String descriptorVariable, String descriptor) {
		return "(" + objectVariable + ")-[:HAS_DESCRIPTOR]-(" + descriptorVariable + ":Descriptor {name: '" + descriptor + "'})";
	}
}
