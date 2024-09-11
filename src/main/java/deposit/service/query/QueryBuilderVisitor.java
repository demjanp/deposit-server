package deposit.service.query;

import deposit.service.query.elements.*;
import java.util.ArrayList;
import java.util.List;

public class QueryBuilderVisitor extends DepositQueryBaseVisitor<ParsedQuery> {
    private ParsedQuery parsedQuery;

    public QueryBuilderVisitor() {
        this.parsedQuery = new ParsedQuery();
    }

    @Override
    public ParsedQuery visitQuery(DepositQueryParser.QueryContext ctx) {
        visitSelectClause(ctx.selectClause());
        if (ctx.whereClause() != null) {
            visitWhereClause(ctx.whereClause());
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
            visitAggregationFunction(ctx.aggregationFunction());
            return parsedQuery;
        }
        if (ctx.identifier(0) != null) {
            String className = ctx.identifier(0).getText();
            String descriptor = ctx.identifier().size() > 1 ? ctx.identifier(1).getText() : null;
            String alias = ctx.alias() != null ? ctx.alias().getText() : "";
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
        visitLogicalExpression(ctx.logicalExpression());
        return parsedQuery;
    }

    public ParsedQuery visitLogicalExpression(DepositQueryParser.LogicalExpressionContext ctx) {
        if (ctx.logicalTerm() != null) {
            List<WhereCondition> subConditions = new ArrayList<>();
            List<String> logicalOperators = new ArrayList<>();
            for (int i = 0; i < ctx.logicalTerm().size(); i++) {
                subConditions.add(extractWhereCondition(ctx.logicalTerm(i)));
                if (i < ctx.logicalOp().size()) {
                    logicalOperators.add(ctx.logicalOp(i).getText());
                }
            }
            CompositeCondition compositeCondition = new CompositeCondition(subConditions, logicalOperators);
            parsedQuery.addWhereCondition(compositeCondition);
        }
        return parsedQuery;
    }

    public WhereCondition extractWhereCondition(DepositQueryParser.LogicalTermContext ctx) {
        WhereCondition condition = null;

        // Handle NOT condition
        boolean negated = false;
        if (ctx.getChild(0).getText().equals("NOT")) {
            negated = true;
            // Skip the NOT keyword and evaluate the following condition
            ctx = (DepositQueryParser.LogicalTermContext) ctx.getChild(1);
        }

        if (ctx.logicalExpression() != null) {
            // Process a composite condition
            List<WhereCondition> subConditions = new ArrayList<>();
            List<String> logicalOperators = new ArrayList<>();
            for (int i = 0; i < ctx.logicalExpression().logicalTerm().size(); i++) {
                subConditions.add(extractWhereCondition(ctx.logicalExpression().logicalTerm(i)));
                if (i < ctx.logicalExpression().logicalOp().size()) {
                    logicalOperators.add(ctx.logicalExpression().logicalOp(i).getText());
                }
            }
            condition = new CompositeCondition(subConditions, logicalOperators);
        } else if (ctx.atomicCondition() != null) {
            // Process atomic conditions
            if (ctx.atomicCondition().relationship() != null) {
                return extractRelationshipCondition(ctx.atomicCondition().relationship());
            }

            // Extract left and right operands for the atomic condition
            String leftClass = ctx.atomicCondition().identifier(0).getText();
            String leftDescriptor = null;
            String leftAlias = null;
            if (ctx.atomicCondition().identifier().size() > 1) {
                leftDescriptor = ctx.atomicCondition().identifier(1).getText();
                leftAlias = QueryUtils.generateAliasForDescriptor(leftClass, leftDescriptor);
            } else {
                leftAlias = QueryUtils.generateAliasForClass(leftClass);
            }

            String leftOperand = "d_" + leftAlias;
            SelectItem leftSelectItem = parsedQuery.getSelectItemByAlias(leftAlias);
            if (leftSelectItem != null) {
                leftOperand = leftSelectItem.getVariable();
            }

            String rightOperand = ctx.atomicCondition().value().getText();
            String rightClass = null;
            String rightDescriptor = null;
            String rightAlias = null;
            if (!isLiteralValue(ctx.atomicCondition().value())) {
                rightClass = ctx.atomicCondition().identifier(0).getText();
                if (ctx.atomicCondition().identifier().size() > 1) {
                    rightDescriptor = ctx.atomicCondition().identifier(1).getText();
                    rightAlias = QueryUtils.generateAliasForDescriptor(rightClass, rightDescriptor);
                } else {
                    rightAlias = QueryUtils.generateAliasForClass(rightClass);
                }
                rightOperand = "d_" + rightAlias;
                SelectItem rightSelectItem = parsedQuery.getSelectItemByAlias(rightAlias);
                if (rightSelectItem != null) {
                    rightOperand = rightSelectItem.getVariable();
                }
            }

            condition = new WhereCondition(leftOperand, ctx.atomicCondition().comparator().getText(), rightOperand,
                    leftClass, rightClass, leftDescriptor, rightDescriptor, leftAlias, rightAlias);
        }

        if (condition == null) {
            throw new IllegalStateException("No valid condition found in the query.");
        }

        // Apply negation if NOT was present
        if (negated) {
            condition.setNegated(true);  // Assuming WhereCondition has a setNegated() method
        }

        return condition;
    }



    private WhereCondition extractRelationshipCondition(DepositQueryParser.RelationshipContext ctx) {
        String leftClassOrAlias = QueryUtils.sanitizeAlias(QueryUtils.stripQuotes(ctx.identifier(0).getText()));
        String rightClassOrAlias = QueryUtils.sanitizeAlias(QueryUtils.stripQuotes(ctx.identifier(1).getText()));
        SelectItem leftAliasInSelect = parsedQuery.getSelectItemByAlias(leftClassOrAlias);
        SelectItem rightAliasInSelect = parsedQuery.getSelectItemByAlias(rightClassOrAlias);
        String leftOperand = leftAliasInSelect != null ? leftAliasInSelect.getObjectVariable() : "o_" + leftClassOrAlias;
        String rightOperand = rightAliasInSelect != null ? rightAliasInSelect.getObjectVariable() : "o_" + rightClassOrAlias;
        String leftClassName = leftAliasInSelect != null ? leftAliasInSelect.getClassName() : leftClassOrAlias;
        String rightClassName = rightAliasInSelect != null ? rightAliasInSelect.getClassName() : rightClassOrAlias;
        String label = QueryUtils.stripQuotes(ctx.label().getText());
        String relationshipType = ctx.getText().contains("<-") ? "INCOMING" : (ctx.getText().contains("->") ? "OUTGOING" : "BIDIRECTIONAL");
        String minHops = ctx.minHops() != null ? ctx.minHops().getText() : null;
        String maxHops = ctx.maxHops() != null ? ctx.maxHops().getText() : null;
        return new WhereCondition(leftOperand, "--", rightOperand, leftClassName, rightClassName,
                null, null, leftClassOrAlias, rightClassOrAlias, label, relationshipType, minHops, maxHops);
    }

    private boolean isLiteralValue(DepositQueryParser.ValueContext valueCtx) {
        return valueCtx != null && (valueCtx.NUMBER() != null || valueCtx.QUOTED_STRING() != null);
    }

    @Override
    public ParsedQuery visitRelationship(DepositQueryParser.RelationshipContext ctx) {
        extractRelationshipCondition(ctx);
        return parsedQuery;
    }
}
