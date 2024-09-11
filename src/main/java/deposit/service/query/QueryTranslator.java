package deposit.service.query;

import deposit.service.query.elements.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class QueryTranslator {

	public static String translateToCypher(String query) {
		ParsedQuery parsedQuery = QueryParser.parse(query);
		StringBuilder cypherQuery = new StringBuilder();

		// Build MATCH, OPTIONAL MATCH, and WHERE clauses
		String matchClause = buildMatchClause(parsedQuery);
		String optionalMatchClause = buildOptionalMatchClause(parsedQuery);

		// Collect the descriptors used in the WHERE clause
		Set<String> whereClauseDescriptors = new HashSet<>();
		String whereClause = buildWhereClause(parsedQuery, whereClauseDescriptors);

		if (!matchClause.isEmpty()) {
			cypherQuery.append(matchClause).append("\n");
		}

		// Build the first WITH clause including object and descriptor variables, ensuring no duplicates
		Set<String> withVariables = new HashSet<>();
		for (SelectItem selectItem : parsedQuery.getSelectItems()) {
			withVariables.add(selectItem.getObjectVariable());
			if (selectItem.getDescriptor() != null) {
				withVariables.add(selectItem.getVariable());
			}
		}
		// Add descriptors collected from WHERE clause
		withVariables.addAll(whereClauseDescriptors);

		if (!withVariables.isEmpty() && !optionalMatchClause.isEmpty()) {
			cypherQuery.append("WITH ").append(String.join(", ", withVariables)).append("\n");
		}

		if (!optionalMatchClause.isEmpty()) {
			cypherQuery.append(optionalMatchClause).append("\n");
		}

		// Only add the second WITH clause if we have descriptors to include in the WHERE clause
		if (!whereClause.isEmpty() && !whereClauseDescriptors.isEmpty()) {
			cypherQuery.append("WITH ").append(String.join(", ", whereClauseDescriptors)).append("\n");
		}

		if (!whereClause.isEmpty()) {
			cypherQuery.append(whereClause).append("\n");
		}

		return cypherQuery.toString();
	}

    static String buildMatchClause(ParsedQuery parsedQuery) {
        StringBuilder matchClause = new StringBuilder("MATCH ");
        List<String> matchPatterns = new ArrayList<>();
        Set<String> matchedClasses = new HashSet<>();
        Set<String> matchedDescriptors = new HashSet<>();

        // Process SelectItems for matching classes and descriptors
        for (SelectItem selectItem : parsedQuery.getSelectItems()) {
            matchPatterns.add(generateMatchForClass(selectItem));
            matchedClasses.add(selectItem.getClassName());
            if (selectItem.getDescriptor() != null) {
                matchPatterns.add(generateMatchForDescriptor(selectItem));
                matchedDescriptors.add(selectItem.getClassName() + "." + selectItem.getDescriptor());
            }
        }

        // Process WhereConditions for additional matches
        for (WhereCondition condition : parsedQuery.getWhereConditions()) {
            handleWhereCondition(condition, parsedQuery, matchPatterns, matchedClasses, matchedDescriptors);
        }

        matchClause.append(String.join(", ", matchPatterns));
        return matchClause.toString();
    }

	private static void handleWhereCondition(WhereCondition condition, ParsedQuery parsedQuery, List<String> matchPatterns, Set<String> matchedClasses, Set<String> matchedDescriptors) {
		if (condition instanceof CompositeCondition) {
            CompositeCondition compositeCondition = (CompositeCondition) condition;
            for (WhereCondition subCondition : compositeCondition.getConditions()) {
                handleWhereCondition(subCondition, parsedQuery, matchPatterns, matchedClasses, matchedDescriptors);
            }
        } else if (condition.getRelationshipType() != null) {
            // TODO Generate MATCH pattern for the Relationship
            System.out.println("REL: " + condition.toString()); // DEBUG
        } else {
            // Reuse the logic for both left and right sides
			processConditionSide(condition.getLeftClass(), condition.getLeftDescriptor(), condition.getLeftAlias(), matchPatterns, matchedClasses, matchedDescriptors, parsedQuery);
			processConditionSide(condition.getRightClass(), condition.getRightDescriptor(), condition.getRightAlias(), matchPatterns, matchedClasses, matchedDescriptors, parsedQuery);
		}
	}

	private static void processConditionSide(String className, String descriptorName, String alias, List<String> matchPatterns, Set<String> matchedClasses, Set<String> matchedDescriptors, ParsedQuery parsedQuery) {

        if (parsedQuery.getSelectItemByAlias(alias) != null) {
            // Skip if already processed in SELECT clause
            return;
        }

        // Handle class matching
		if (className != null && !matchedClasses.contains(className)) {
			matchPatterns.add(generateMatchForClassFromCondition(className, parsedQuery));
			matchedClasses.add(className);
		}

		// Handle descriptor matching
		if (descriptorName != null) {
			String descriptorKey = className + "." + descriptorName;
			if (!matchedDescriptors.contains(descriptorKey)) {
				matchPatterns.add(generateMatchForDescriptorFromCondition(className, descriptorName));
				matchedDescriptors.add(descriptorKey);
			}
		}
	}

    static String buildOptionalMatchClause(ParsedQuery parsedQuery) {
        List<String> optionalMatchPatterns = new ArrayList<>();
        List<String[]> relationshipPairs = getRelationshipPairs(parsedQuery.getSelectItems(), parsedQuery.getRelationshipConditions());

        // Add explicit relationship matches
        for (RelationshipCondition condition : parsedQuery.getRelationshipConditions()) {
            String relationshipMatch = buildRelationshipMatch(condition, true);
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

    static String buildWhereClause(ParsedQuery parsedQuery, Set<String> descriptors) {
        List<String> conditions = new ArrayList<>();

        for (WhereCondition condition : parsedQuery.getWhereConditions()) {
            addConditionAndDescriptors(condition, parsedQuery, conditions, descriptors);
        }

        String conditionsString = String.join(" ", conditions);

        return "WHERE " + conditionsString;
    }

	private static void addConditionAndDescriptors(WhereCondition condition, ParsedQuery parsedQuery, List<String> conditions, Set<String> descriptors) {
		if (condition instanceof CompositeCondition) {
			CompositeCondition compositeCondition = (CompositeCondition) condition;
			StringBuilder compositeClause = new StringBuilder(condition.isNegated() ? "NOT (" : "(");
			List<WhereCondition> subConditions = compositeCondition.getConditions();
			List<String> logicalOperators = compositeCondition.getLogicalOperators();
			for (int i = 0; i < subConditions.size(); i++) {
				List<String> subConditionClause = new ArrayList<>();
				addConditionAndDescriptors(subConditions.get(i), parsedQuery, subConditionClause, descriptors);
				compositeClause.append(subConditionClause.get(subConditionClause.size() - 1));
				if (i < logicalOperators.size()) {
					compositeClause.append(" ").append(logicalOperators.get(i)).append(" ");
				}
			}
			compositeClause.append(")");
			conditions.add(compositeClause.toString());
		} else {
			if (condition.getRelationshipType() != null) {
				// TODO: Generate WHERE clause for the Relationship
				conditions.add("rel_X IS NOT NULL");
			} else {
				String leftOperand = replaceWithVariable(condition.getLeftOperand(), parsedQuery);
				String rightOperand = replaceWithVariable(condition.getRightOperand(), parsedQuery);
				String clause = leftOperand + " " + condition.getOperator() + " " + rightOperand;

				// Wrap in NOT(...) if the condition is negated
				if (condition.isNegated()) {
					clause = "NOT (" + clause + ")";
				}

				conditions.add(clause);
				if (condition.getLeftDescriptor() != null) {
					descriptors.add("d_" + condition.getLeftClass() + "_" + condition.getLeftDescriptor());
				}
				if (condition.getRightDescriptor() != null) {
					descriptors.add("d_" + condition.getRightClass() + "_" + condition.getRightDescriptor());
				}
			}
		}
	}


    private static String generateMatchForClassFromCondition(String className, ParsedQuery parsedQuery) {
        // Generate match clause for a valid class
        if (className == null || className.isEmpty()) {
            return null;
        }
        String objectVariable = "o_" + QueryUtils.generateAliasForClass(className);
        return "(:Namespace {name: $namespaceName})-[:CONTAINS]->(" + objectVariable + ":Object)" +
                "-[:MEMBER_OF]->(:Class {name: '" + className + "'})";
    }

    // Generate match pattern for a descriptor not in the SELECT clause but in WHERE clause
    private static String generateMatchForDescriptorFromCondition(String className, String descriptor) {
        String objectVariable = "o_" + QueryUtils.generateAliasForClass(className);
        String descriptorVariable = "d_" + QueryUtils.generateAliasForDescriptor(className, descriptor);
        return "(" + objectVariable + ")-[:HAS_DESCRIPTOR]-(" + descriptorVariable + ":Descriptor {name: '" + descriptor + "'})";
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

    private static List<String[]> getRelationshipPairs(List<SelectItem> selectItems, List<RelationshipCondition> relationshipConditions) {
        List<String[]> relationshipPairs = new ArrayList<>();
        Set<String> definedRelationships = new HashSet<>();

        // Collect explicitly defined relationships
        for (RelationshipCondition condition : relationshipConditions) {
            String pairForward = condition.getLeftVariable() + "-" + condition.getRightVariable();
            definedRelationships.add(pairForward);
        }

        if (selectItems.isEmpty()) {
            return relationshipPairs;
        }

        String mainObjectVar = selectItems.get(0).getObjectVariable();
        for (int i = 1; i < selectItems.size(); i++) {
            String relatedObjectVar = selectItems.get(i).getObjectVariable();
            String pairForward = mainObjectVar + "-" + relatedObjectVar;

            if (!definedRelationships.contains(pairForward)) {
                relationshipPairs.add(new String[]{mainObjectVar, relatedObjectVar});
            }
        }

        return relationshipPairs;
    }

    private static String buildRelationshipMatch(RelationshipCondition condition, boolean isExplicit) {
        String relationshipPart = buildRelationshipPart(condition, isExplicit);
        String direction = getCypherDirection(condition.getRelationshipType());

        if (direction.equals("<-")) {
            return "(%s)<-[%s]-(%s)".formatted(condition.getRightVariable(), relationshipPart, condition.getLeftVariable());
        } else if (direction.equals("->")) {
            return "(%s)-[%s]->(%s)".formatted(condition.getLeftVariable(), relationshipPart, condition.getRightVariable());
        } else {
            return "(%s)-[%s]-(%s)".formatted(condition.getLeftVariable(), relationshipPart, condition.getRightVariable());
        }
    }

    private static String buildRelationshipPart(RelationshipCondition condition, boolean isExplicit) {
        String relVariable = condition.getRelVariable();
        String hops = condition.getMinHops() != null || condition.getMaxHops() != null
                ? "*" + (condition.getMinHops() != null ? condition.getMinHops() : "") + ".." + (condition.getMaxHops() != null ? condition.getMaxHops() : "")
                : "";

        if (isExplicit && !condition.getLabel().isEmpty()) {
            return "%s:RELATED_TO%s {label: '%s'}".formatted(relVariable, hops, condition.getLabel());
        }
        return "%s:RELATED_TO%s".formatted(relVariable, hops);  // No label for undefined relationships
    }

    // This method handles the generation of unspecified relationships
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

    private static String generateMatchForClass(SelectItem selectItem) {
        String objectVariable = selectItem.getObjectVariable();
        return "(:Namespace {name: $namespaceName})-[:CONTAINS]->(" + objectVariable + ":Object)" +
                "-[:MEMBER_OF]->(:Class {name: '" + selectItem.getClassName() + "'})";
    }

    private static String generateMatchForDescriptor(SelectItem selectItem) {
        String objectVariable = selectItem.getObjectVariable();
        String descriptorVariable = selectItem.getVariable();
        return "(" + objectVariable + ")-[:HAS_DESCRIPTOR]-(" + descriptorVariable + ":Descriptor {name: '" + selectItem.getDescriptor() + "'})";
    }

}
