package deposit.service.query;

import deposit.service.query.elements.*;

public class QueryBuilderVisitor extends DepositQueryBaseVisitor<ParsedQuery> {

    private ParsedQuery parsedQuery;

    public QueryBuilderVisitor() {
        this.parsedQuery = new ParsedQuery();
    }

    @Override
    public ParsedQuery visitQuery(DepositQueryParser.QueryContext ctx) {
        // Visit SELECT clause
        visitSelectClause(ctx.selectClause());

        // Visit WHERE clause if it exists
        if (ctx.whereClause() != null) {
            visitWhereClause(ctx.whereClause());
        }

        return parsedQuery;
    }

    @Override
    public ParsedQuery visitSelectClause(DepositQueryParser.SelectClauseContext ctx) {
        // Handle DISTINCT keyword if present
        if (ctx.DISTINCT() != null) {
            parsedQuery.setDistinct(true);
        }

        // Process each select item
        for (DepositQueryParser.SelectItemContext selectItemContext : ctx.selectItem()) {
            visitSelectItem(selectItemContext);
        }
        return parsedQuery;
    }

    @Override
    public ParsedQuery visitSelectItem(DepositQueryParser.SelectItemContext ctx) {
        if (ctx.aggregationFunction() != null) {
            visitAggregationFunction(ctx.aggregationFunction());
            return parsedQuery;
        }
        if (ctx.identifier(0) != null) {
            String className = ctx.identifier(0).getText();
            String descriptor = ctx.identifier().size() > 1 ? ctx.identifier(1).getText() : null;
            String alias = ctx.alias() != null ? ctx.alias().getText() : "";

            // Collect class names directly during parsing
            parsedQuery.addClassName(className);

            SelectItem selectItem = new SelectItem(className, descriptor, alias);
            parsedQuery.addSelectItem(selectItem);
        }
        return parsedQuery;
    }

    @Override
    public ParsedQuery visitAggregationFunction(DepositQueryParser.AggregationFunctionContext ctx) {
        String functionName = ctx.functionName().getText();
        String className = ctx.identifier(0).getText();
        String descriptor = ctx.identifier().size() > 1 ? ctx.identifier(1).getText() : null;
        String alias = ctx.alias() != null ? ctx.alias().getText() : "";
        SelectItem selectItem = new SelectItem(className, descriptor, alias, functionName);
        parsedQuery.addSelectItem(selectItem);
        return parsedQuery;
    }

    @Override
    public ParsedQuery visitWhereClause(DepositQueryParser.WhereClauseContext ctx) {

        // We start with the first condition
        visitCondition(ctx.condition(0));

        // For each subsequent condition, we must also handle the logical operator
        for (int i = 1; i < ctx.condition().size(); i++) {
            String logicalOperator = ctx.logicalOp(i - 1).getText();
            parsedQuery.addLogicalOperator(logicalOperator);
            visitCondition(ctx.condition(i));
        }

        return parsedQuery;
    }

    @Override
    public ParsedQuery visitCondition(DepositQueryParser.ConditionContext ctx) {
        if (ctx.expression() != null) {
            // Handle expression-based condition
            String className = ctx.expression().identifier(0).getText();
            String descriptor = ctx.expression().identifier().size() > 1 ? ctx.expression().identifier(1).getText() : null;

            parsedQuery.addClassName(className);  // Collect class name

            String leftOperand = "o_" + QueryUtils.sanitizeAlias(className);
            if (descriptor != null) {
                leftOperand = "d_" + QueryUtils.sanitizeAlias(className + "_" + descriptor);
            }

            String operator = ctx.comparator().getText();
            String rightOperand = ctx.value().getText();

            // Store class name in the WhereCondition
            WhereCondition condition = new WhereCondition(leftOperand, operator, rightOperand, className);
            parsedQuery.addWhereCondition(condition);
        } else if (ctx.relationship() != null) {
            // Handle relationship-based condition
            visitRelationship(ctx.relationship());  // Visit the relationship
        }
        return parsedQuery;
    }

    @Override
    public ParsedQuery visitRelationship(DepositQueryParser.RelationshipContext ctx) {
        String leftClassOrAlias = QueryUtils.sanitizeAlias(QueryUtils.stripQuotes(ctx.identifier(0).getText()));
        String rightClassOrAlias = QueryUtils.sanitizeAlias(QueryUtils.stripQuotes(ctx.identifier(1).getText()));

        SelectItem leftAliasInSelect = parsedQuery.getSelectItemByAlias(leftClassOrAlias);
        SelectItem rightAliasInSelect = parsedQuery.getSelectItemByAlias(rightClassOrAlias);

        String leftOperand = leftAliasInSelect != null ? leftAliasInSelect.getObjectVariable() : "o_" + leftClassOrAlias;
        String rightOperand = rightAliasInSelect != null ? rightAliasInSelect.getObjectVariable() : "o_" + rightClassOrAlias;

        String leftClassName = leftAliasInSelect != null ? leftAliasInSelect.getClassName() : leftClassOrAlias;
        String rightClassName = rightAliasInSelect != null ? rightAliasInSelect.getClassName() : rightClassOrAlias;

        String relationshipType = ctx.getText().contains("<-") ? "INCOMING" : (ctx.getText().contains("->") ? "OUTGOING" : "BIDIRECTIONAL");
        String minHops = ctx.minHops() != null ? ctx.minHops().getText() : null;
        String maxHops = ctx.maxHops() != null ? ctx.maxHops().getText() : null;
        String label = QueryUtils.stripQuotes(ctx.label().getText());

        // Store class names in the RelationshipCondition
        RelationshipCondition relationshipCondition = new RelationshipCondition(leftOperand, rightOperand, leftClassName, rightClassName, label, relationshipType, minHops, maxHops);
        parsedQuery.addRelationshipCondition(relationshipCondition);
        return parsedQuery;
    }
}
