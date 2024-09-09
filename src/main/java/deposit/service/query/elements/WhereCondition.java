package deposit.service.query.elements;

public class WhereCondition {
    private String leftOperand;
    private String operator;
    private String rightOperand;
    private String className;
    private String logicalOperator;

    public WhereCondition(String leftOperand, String operator, String rightOperand, String className) {
        this.leftOperand = leftOperand;
        this.operator = operator;
        this.rightOperand = rightOperand;
        this.className = className;
    }

    public String getLeftOperand() {
        return leftOperand;
    }

    public void setLeftOperand(String leftOperand) {
        this.leftOperand = leftOperand;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getRightOperand() {
        return rightOperand;
    }

    public void setRightOperand(String rightOperand) {
        this.rightOperand = rightOperand;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getLogicalOperator() {
        return logicalOperator;
    }

    public void setLogicalOperator(String logicalOperator) {
        this.logicalOperator = logicalOperator;
    }

    @Override
    public String toString() {
        return "WhereCondition { " +
                "className='" + className + '\'' +
                ", leftOperand='" + leftOperand + '\'' +
                ", operator='" + operator + '\'' +
                ", rightOperand='" + rightOperand + '\'' +
                (logicalOperator != null ? ", logicalOperator='" + logicalOperator + '\'' : "") +
                " }";
    }
}
