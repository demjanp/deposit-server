package deposit.service.query.elements;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

public class ParsedQuery {

    private List<SelectItem> selectItems;
    private List<WhereCondition> whereConditions;
    private List<RelationshipCondition> relationshipConditions;
    private List<String> logicalOperators;
    private Set<String> classNames;

    // Flag to indicate if the query is DISTINCT
    private boolean distinct;

    public ParsedQuery() {
        this.selectItems = new ArrayList<>();
        this.whereConditions = new ArrayList<>();
        this.relationshipConditions = new ArrayList<>();
        this.logicalOperators = new ArrayList<>();
        this.classNames = new HashSet<>();
        this.distinct = false;
    }

    // Method to add a select item
    public void addSelectItem(SelectItem selectItem) {
        this.selectItems.add(selectItem);
    }

    // Method to retrieve all select items
    public List<SelectItem> getSelectItems() {
        return this.selectItems;
    }

    // Method to retrieve a select item by alias or class name (or class-descriptor pair)
    public SelectItem getSelectItemByAlias(String alias) {
        // Search for an exact match by alias, class name, or class-descriptor pair
        for (SelectItem item : selectItems) {

            // Handle alias lookup for both class and class.descriptor pairs
            String fullKey = item.getDescriptor() != null ? item.getClassName() + "." + item.getDescriptor() : item.getClassName();

            // Check for an alias or class match
            if (item.getAlias().equals(alias) || fullKey.equals(alias) || item.getClassName().equals(alias)) {
                return item;
            }
        }
        return null;
    }

    // Method to add a where condition
    public void addWhereCondition(WhereCondition whereCondition) {
        this.whereConditions.add(whereCondition);
    }

    // Method to retrieve all where conditions
    public List<WhereCondition> getWhereConditions() {
        return this.whereConditions;
    }

    // Method to add a relationship condition
    public void addRelationshipCondition(RelationshipCondition relationshipCondition) {
        this.relationshipConditions.add(relationshipCondition);
    }

    // Method to retrieve all relationship conditions
    public List<RelationshipCondition> getRelationshipConditions() {
        return this.relationshipConditions;
    }

    // Method to add a logical operator (AND, OR) between conditions
    public void addLogicalOperator(String logicalOperator) {
        this.logicalOperators.add(logicalOperator);
    }

    // Method to retrieve all logical operators
    public List<String> getLogicalOperators() {
        return this.logicalOperators;
    }

    public void addClassName(String className) {
        this.classNames.add(className);
    }

    public Set<String> getClassNames() {
        return this.classNames;
    }

    // Method to check if the query uses DISTINCT
    public boolean isDistinct() {
        return distinct;
    }

    // Method to set the DISTINCT flag
    public void setDistinct(boolean distinct) {
        this.distinct = distinct;
    }

    // Method to print the parsed query (useful for debugging)
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("ParsedQuery {\n");

        // Print DISTINCT flag
        builder.append("  DISTINCT: ").append(distinct).append("\n");

        // Print all select items
        builder.append("  Select Items: \n");
        for (SelectItem item : selectItems) {
            builder.append("    ").append(item.toString()).append("\n");
        }

        // Print all where conditions
        if (!whereConditions.isEmpty()) {
            builder.append("  Where Conditions: \n");
            for (int i = 0; i < whereConditions.size(); i++) {
                builder.append("    ").append(whereConditions.get(i).toString()).append("\n");
                if (i < logicalOperators.size()) {
                    builder.append("    Logical Operator: ").append(logicalOperators.get(i)).append("\n");
                }
            }
        } else {
            builder.append("  No Where Conditions\n");
        }

        // Print all relationship conditions
        if (!relationshipConditions.isEmpty()) {
            builder.append("  Relationship Conditions: \n");
            for (RelationshipCondition relationship : relationshipConditions) {
                builder.append("    ").append(relationship.toString()).append("\n");
            }
        } else {
            builder.append("  No Relationship Conditions\n");
        }

        builder.append("}");
        return builder.toString();
    }
}
