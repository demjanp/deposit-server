package deposit.service.query.elements;

public class RelationshipCondition {
    private String leftVariable;
    private String rightVariable;
    private String leftClassName;  // New field for left class name
    private String rightClassName;  // New field for right class name
    private String label;
    private String relationshipType;
    private String minHops;
    private String maxHops;

    public RelationshipCondition(String leftVariable, String rightVariable, String leftClassName, String rightClassName,
                                 String label, String relationshipType, String minHops, String maxHops) {
        this.leftVariable = leftVariable;
        this.rightVariable = rightVariable;
        this.leftClassName = leftClassName;
        this.rightClassName = rightClassName;
        this.label = label;
        this.relationshipType = relationshipType;
        this.minHops = minHops;
        this.maxHops = maxHops;
    }

    public String getLeftVariable() {
        return leftVariable;
    }

    public String getRightVariable() {
        return rightVariable;
    }

    public String getLeftClassName() {
        return leftClassName;
    }

    public String getRightClassName() {
        return rightClassName;
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

    @Override
    public String toString() {
        return "RelationshipCondition { " +
                "leftClassName='" + leftClassName + '\'' +
                ", rightClassName='" + rightClassName + '\'' +
                ", leftVariable='" + leftVariable + '\'' +
                ", rightVariable='" + rightVariable + '\'' +
                ", label='" + label + '\'' +
                ", relationshipType='" + relationshipType + '\'' +
                ", minHops='" + minHops + '\'' +
                ", maxHops='" + maxHops + '\'' +
                " }";
    }
}
