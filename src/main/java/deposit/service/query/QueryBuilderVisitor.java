package deposit.service.query;

import deposit.service.query.elements.*;
import java.util.ArrayList;
import java.util.List;

public class QueryBuilderVisitor extends DepositQueryBaseVisitor<ParsedQuery> {
	private ParsedQuery parsedQuery;

	public QueryBuilderVisitor() {
		initializeParsedQuery();
	}

	private void initializeParsedQuery() {
		this.parsedQuery = new ParsedQuery();
	}

	@Override
	public ParsedQuery visitQuery(DepositQueryParser.QueryContext ctx) {
		visitSelectClause(ctx.selectClause());
		if (ctx.whereClause() != null) {
			visitWhereClause(ctx.whereClause());
		}
		if (ctx.orderByClause() != null) {
			visitOrderByClause(ctx.orderByClause());
		}
		return parsedQuery;
	}
	
	@Override
	public ParsedQuery visitSelectClause(DepositQueryParser.SelectClauseContext ctx) {
		if (ctx.DISTINCT() != null) {
			parsedQuery.setDistinct(true);
		}
		for (DepositQueryParser.SelectItemContext selectItemContext : ctx.selectItem()) {
			visitSelectItem(selectItemContext);
		}
		return parsedQuery;
	}

	@Override
	public ParsedQuery visitSelectItem(DepositQueryParser.SelectItemContext ctx) {
		if (ctx.aggregationFunction() != null) {
			handleAggregationFunction(ctx.aggregationFunction());
		} else {
			handleRegularSelectItem(ctx);
		}
		return parsedQuery;
	}
	
	@Override
	public ParsedQuery visitAggregationFunction(DepositQueryParser.AggregationFunctionContext ctx) {
		handleAggregationFunction(ctx); // Simply call the new helper method.
		return parsedQuery;
	}
	
	@Override
	public ParsedQuery visitWhereClause(DepositQueryParser.WhereClauseContext ctx) {
		DepositQueryParser.LogicalExpressionContext logicalExpression = ctx.logicalExpression();
		if (logicalExpression.logicalTerm() != null) {
			List<WhereCondition> subConditions = new ArrayList<>();
			List<String> logicalOperators = new ArrayList<>();
			for (int i = 0; i < logicalExpression.logicalTerm().size(); i++) {
				subConditions.add(extractWhereCondition(logicalExpression.logicalTerm(i)));
				if (i < logicalExpression.logicalOp().size()) {
					logicalOperators.add(logicalExpression.logicalOp(i).getText());
				}
			}
			CompositeCondition compositeCondition = new CompositeCondition(subConditions, logicalOperators);
			parsedQuery.addWhereCondition(compositeCondition);
		}
		return parsedQuery;
	}
	
	@Override
	public ParsedQuery visitRelationship(DepositQueryParser.RelationshipContext ctx) {
		extractRelationshipCondition(ctx);
		return parsedQuery;
	}
	
	@Override
	public ParsedQuery visitOrderByClause(DepositQueryParser.OrderByClauseContext ctx) {
		for (DepositQueryParser.OrderItemContext orderItemContext : ctx.orderItem()) {
			visitOrderItem(orderItemContext);
		}
		return parsedQuery;
	}
	
	@Override
	public ParsedQuery visitOrderItem(DepositQueryParser.OrderItemContext ctx) {
		OrderByItem orderByItem = handleOrderByItem(ctx);
		parsedQuery.addOrderByItem(orderByItem);
		return parsedQuery;
	}
	
	// visitSelectItem helpers
	
	private SelectItem handleRegularSelectItem(DepositQueryParser.SelectItemContext ctx) {
		if (ctx.identifier(0) != null) {
			String className = ctx.identifier(0).getText();
			String descriptor = ctx.identifier().size() > 1 ? ctx.identifier(1).getText() : null;
			String alias = ctx.alias() != null ? ctx.alias().getText() : "";

			SelectItem selectItem = new SelectItem(className, descriptor, alias);
			parsedQuery.addSelectItem(selectItem);
			return selectItem;
		}
		return null;
	}
	
	private SelectItem handleAggregationFunction(DepositQueryParser.AggregationFunctionContext ctx) {
		String functionName = ctx.functionName().getText();
		String className = ctx.identifier(0).getText();
		String descriptor = ctx.identifier().size() > 1 ? ctx.identifier(1).getText() : null;
		String alias = ctx.alias() != null ? ctx.alias().getText() : "";
		Double functionValue = ctx.NUMBER() != null ? Double.parseDouble(ctx.NUMBER().getText()) : null;
		boolean isDistinct = ctx.DISTINCT() != null;

		SelectItem selectItem = new SelectItem(className, descriptor, alias, functionName, functionValue, isDistinct);
		parsedQuery.addSelectItem(selectItem);
		return selectItem;
	}
	
	
	// visitWhereClause helpers
	
	public WhereCondition extractWhereCondition(DepositQueryParser.LogicalTermContext ctx) {
		// Handle NOT condition
		boolean negated = false;
		if (ctx.getChild(0).getText().equals("NOT")) {
			negated = true;
			ctx = (DepositQueryParser.LogicalTermContext) ctx.getChild(1); // Skip NOT
		}

		WhereCondition condition;
		if (ctx.logicalExpression() != null) {
			condition = handleCompositeCondition(ctx.logicalExpression());
		} else if (ctx.atomicCondition() != null) {
			condition = handleAtomicCondition(ctx.atomicCondition());
		} else {
			throw new IllegalStateException("No valid condition found in the query.");
		}

		// Apply negation if NOT was present
		if (negated) {
			condition.setNegated(true); // Assuming WhereCondition has a setNegated() method
		}

		return condition;
	}
	
	private CompositeCondition handleCompositeCondition(DepositQueryParser.LogicalExpressionContext ctx) {
		List<WhereCondition> subConditions = new ArrayList<>();
		List<String> logicalOperators = new ArrayList<>();

		for (int i = 0; i < ctx.logicalTerm().size(); i++) {
			subConditions.add(extractWhereCondition(ctx.logicalTerm(i)));
			if (i < ctx.logicalOp().size()) {
				logicalOperators.add(ctx.logicalOp(i).getText());
			}
		}

		return new CompositeCondition(subConditions, logicalOperators);
	}

	private WhereCondition handleAtomicCondition(DepositQueryParser.AtomicConditionContext ctx) {
		// Process atomic condition or relationship
		if (ctx.relationship() != null) {
			return extractRelationshipCondition(ctx.relationship());
		}

		// Extract left operand (class and descriptor)
		String leftClass = ctx.identifier(0).getText();
		String leftDescriptor = ctx.identifier().size() > 1 ? ctx.identifier(1).getText() : null;
		String leftAlias = generateAlias(leftClass, leftDescriptor);
		String leftOperand = extractOperand(leftAlias, true);

		// Extract right operand (could be a value or identifier)
		String rightOperand = ctx.value().getText();
		if (!isLiteralValue(ctx.value())) {
			String rightClass = ctx.identifier(0).getText();
			String rightDescriptor = ctx.identifier().size() > 1 ? ctx.identifier(1).getText() : null;
			String rightAlias = generateAlias(rightClass, rightDescriptor);
			rightOperand = extractOperand(rightAlias, false);
		}

		return new WhereCondition(leftOperand, ctx.comparator().getText(), rightOperand,
				leftClass, null, leftDescriptor, null, leftAlias, null);
	}

	private WhereCondition extractRelationshipCondition(DepositQueryParser.RelationshipContext ctx) {
		String leftClassOrAlias = QueryUtils.generateAliasForClass(ctx.identifier(0).getText());
		String rightClassOrAlias = QueryUtils.generateAliasForClass(ctx.identifier(1).getText());
		SelectItem leftAliasInSelect = parsedQuery.getSelectItemByAlias(leftClassOrAlias);
		SelectItem rightAliasInSelect = parsedQuery.getSelectItemByAlias(rightClassOrAlias);
		String leftOperand = leftAliasInSelect != null ? leftAliasInSelect.getObjectVariable() : "o_" + leftClassOrAlias;
		String rightOperand = rightAliasInSelect != null ? rightAliasInSelect.getObjectVariable() : "o_" + rightClassOrAlias;
		String leftClassName = leftAliasInSelect != null ? leftAliasInSelect.getClassName() : leftClassOrAlias;
		String rightClassName = rightAliasInSelect != null ? rightAliasInSelect.getClassName() : rightClassOrAlias;
		String label = ctx.label() != null ? QueryUtils.stripQuotes(ctx.label().getText()) : "";
		String relationshipType = ctx.getText().contains("<-") ? "INCOMING" : (ctx.getText().contains("->") ? "OUTGOING" : "BIDIRECTIONAL");
		String minHops = ctx.minHops() != null ? ctx.minHops().getText() : null;
		String maxHops = ctx.maxHops() != null ? ctx.maxHops().getText() : null;
		boolean asterisk = ctx.asterisk() != null;
		return new WhereCondition(leftOperand, "--", rightOperand, leftClassName, rightClassName,
				null, null, leftClassOrAlias, rightClassOrAlias, label, relationshipType, minHops, maxHops, asterisk);
	}

	private String generateAlias(String className, String descriptor) {
		return descriptor != null
				? QueryUtils.generateAliasForDescriptor(className, descriptor)
				: QueryUtils.generateAliasForClass(className);
	}

	private String extractOperand(String alias, boolean isLeftOperand) {
		SelectItem selectItem = parsedQuery.getSelectItemByAlias(alias);
		if (selectItem != null) {
			return isLeftOperand ? selectItem.getVariable() : selectItem.getObjectVariable();
		}
		return isLeftOperand ? "d_" + alias : "o_" + alias;
	}

	private boolean isLiteralValue(DepositQueryParser.ValueContext valueCtx) {
		return valueCtx != null && (valueCtx.NUMBER() != null || valueCtx.QUOTED_STRING() != null);
	}
	
	// visitOrderByClause helpers
	private OrderByItem handleOrderByItem(DepositQueryParser.OrderItemContext ctx) {
		if (ctx.identifier(0) != null) {
			String className = ctx.identifier(0).getText();
			String descriptor = ctx.identifier().size() > 1 ? ctx.identifier(1).getText() : null;
			SelectItem selectItem = parsedQuery.getSelectItemByAlias(className);
			if (selectItem == null) {
				selectItem = new SelectItem(className, descriptor, "");
			}
			String orderDirection = ctx.orderDirection() != null ? ctx.orderDirection().getText() : "ASC";  // Default to ASC if not provided
			return new OrderByItem(selectItem, orderDirection);
		}
		return null;
	}
}
