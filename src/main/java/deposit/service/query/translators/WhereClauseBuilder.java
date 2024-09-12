package deposit.service.query.translators;

import deposit.service.query.elements.CompositeCondition;
import deposit.service.query.elements.ParsedQuery;
import deposit.service.query.elements.SelectItem;
import deposit.service.query.elements.WhereCondition;

import java.util.ArrayList;
import java.util.List;

public class WhereClauseBuilder {

	public static String buildWhereClause(ParsedQuery parsedQuery) {
		List<String> conditions = new ArrayList<>();
		for (WhereCondition condition : parsedQuery.getWhereConditions()) {
			processCondition(condition, parsedQuery, conditions);
		}
		return assembleWhereClause(conditions);
	}
	
	private static void processCondition(WhereCondition condition, ParsedQuery parsedQuery, List<String> conditions) {
		if (condition instanceof CompositeCondition) {
			processCompositeCondition((CompositeCondition) condition, parsedQuery, conditions);
		} else {
			processAtomicCondition(condition, parsedQuery, conditions);
		}
	}

	private static void processCompositeCondition(CompositeCondition compositeCondition, ParsedQuery parsedQuery, List<String> conditions) {
		StringBuilder compositeClause = new StringBuilder(compositeCondition.isNegated() ? "NOT (" : "(");
		List<WhereCondition> subConditions = compositeCondition.getConditions();
		List<String> logicalOperators = compositeCondition.getLogicalOperators();

		for (int i = 0; i < subConditions.size(); i++) {
			List<String> subConditionClause = new ArrayList<>();
			processCondition(subConditions.get(i), parsedQuery, subConditionClause);
			compositeClause.append(subConditionClause.get(0));
			if (i < logicalOperators.size()) {
				compositeClause.append(" ").append(logicalOperators.get(i)).append(" ");
			}
		}

		compositeClause.append(")");
		conditions.add(compositeClause.toString());
	}

	private static void processAtomicCondition(WhereCondition condition, ParsedQuery parsedQuery, List<String> conditions) {
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

	private static String replaceWithVariable(String operand, ParsedQuery parsedQuery) {
		SelectItem selectItem = parsedQuery.getSelectItemByAlias(operand);

		if (selectItem != null) {
			return resolveSelectItemVariable(selectItem);
		}
		return operand;
	}

	private static String resolveSelectItemVariable(SelectItem selectItem) {
		if (selectItem.getDescriptor() == null) {
			return resolveObjectVariable(selectItem);
		}
		return resolveDescriptorVariable(selectItem);
	}

	private static String resolveObjectVariable(SelectItem selectItem) {
		return selectItem.getObjectVariable();
	}

	private static String resolveDescriptorVariable(SelectItem selectItem) {
		return selectItem.getVariable();
	}

	private static String assembleWhereClause(List<String> conditions) {
		return String.join(" AND ", conditions);
	}
}
