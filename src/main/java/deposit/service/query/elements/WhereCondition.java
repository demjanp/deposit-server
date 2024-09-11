package deposit.service.query.elements;

public class WhereCondition {
    private String leftOperand;
    private String operator;
    private String rightOperand;
    private String leftClass;
    private String rightClass;
    private String leftDescriptor;
    private String rightDescriptor;
    private String leftAlias;
    private String rightAlias;
    private String logicalOperator;
    private boolean negated;

    // Fields for relationship conditions
    private String label;
    private String relationshipType;
    private String minHops;
    private String maxHops;

    // Constructor for non-relationship conditions
    public WhereCondition(String leftOperand, String operator, String rightOperand,
                          String leftClass, String rightClass, String leftDescriptor,
                          String rightDescriptor, String leftAlias, String rightAlias) {
        this.leftOperand = leftOperand;
        this.operator = operator;
        this.rightOperand = rightOperand;
        this.leftClass = leftClass;
        this.rightClass = rightClass;
        this.leftDescriptor = leftDescriptor;
        this.rightDescriptor = rightDescriptor;
        this.leftAlias = leftAlias;
        this.rightAlias = rightAlias;
        this.negated = false;
    }

    // New constructor for relationship conditions
    public WhereCondition(String leftOperand, String operator, String rightOperand,
                          String leftClass, String rightClass, String leftDescriptor,
                          String rightDescriptor, String leftAlias, String rightAlias,
                          String label, String relationshipType, String minHops, String maxHops) {
        this(leftOperand, operator, rightOperand, leftClass, rightClass, leftDescriptor, rightDescriptor, leftAlias, rightAlias);
        this.label = label;
        this.relationshipType = relationshipType;
        this.minHops = minHops;
        this.maxHops = maxHops;
    }

    public String getLeftOperand() {
        return leftOperand;
    }

    public String getRightOperand() {
        return rightOperand;
    }

    public String getOperator() {
        return operator;
    }

    public String getLeftClass() {
        return leftClass;
    }

    public String getRightClass() {
        return rightClass;
    }

    public String getLeftDescriptor() {
        return leftDescriptor;
    }

    public String getRightDescriptor() {
        return rightDescriptor;
    }

    public String getLeftAlias() {
        return leftAlias;
    }

    public String getRightAlias() {
        return rightAlias;
    }

    public boolean isNegated() {
        return negated;
    }

    public String getLabel() {
        return label;
    }

    public String getRelationshipType() {
        return relationshipType;
    }

    public String getMinHops() {
        return minHops;
    }

    public String getMaxHops() {
        return maxHops;
    }

    public void setLogicalOperator(String logicalOperator) {
        this.logicalOperator = logicalOperator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public void setNegated(boolean negated) {
        this.negated = negated;
    }

    @Override
    public String toString() {
        if (label != null) {
            return "RelationshipCondition { " +
                    "leftClass='" + leftClass + '\'' +
                    ", rightClass='" + rightClass + '\'' +
                    ", leftOperand='" + leftOperand + '\'' +
                    ", rightOperand='" + rightOperand + '\'' +
                    ", label='" + label + '\'' +
                    ", relationshipType='" + relationshipType + '\'' +
                    ", minHops='" + minHops + '\'' +
                    ", maxHops='" + maxHops + '\'' +
                    " }";
        }
        return "WhereCondition { " +
                "leftDescriptor='" + leftDescriptor + '\'' +
                ", rightDescriptor='" + rightDescriptor + '\'' +
                ", leftOperand='" + leftOperand + '\'' +
                ", operator='" + operator + '\'' +
                ", rightOperand='" + rightOperand + '\'' +
                " }";
    }
}
