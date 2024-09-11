package deposit.service.query.elements;

import java.util.List;

public class CompositeCondition extends WhereCondition {
    private List<WhereCondition> conditions;
    private List<String> logicalOperators; // Logical operators (AND, OR, etc.) between conditions

    public CompositeCondition(List<WhereCondition> conditions, List<String> logicalOperators) {
        super(null, null, null, null, null, null, null, null, null); // Use null values as the parent class constructor requires it
        this.conditions = conditions;
        this.logicalOperators = logicalOperators;
    }

    // Get the list of sub-conditions
    public List<WhereCondition> getConditions() {
        return conditions;
    }

    // Get the list of logical operators
    public List<String> getLogicalOperators() {
        return logicalOperators;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("(");
        for (int i = 0; i < conditions.size(); i++) {
            sb.append(conditions.get(i).toString());
            if (i < logicalOperators.size()) {
                sb.append(" ").append(logicalOperators.get(i)).append(" ");
            }
        }
        sb.append(")");
        return sb.toString();
    }
}
