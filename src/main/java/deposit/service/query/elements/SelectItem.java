package deposit.service.query.elements;

import deposit.service.query.QueryUtils;

public class SelectItem {
    private String className;
    private String descriptor;
    private String alias;
    private String objectAlias;
    private String aggregationFunction;
    private String variable;
    private String objectVariable;

    public SelectItem(String className, String descriptor, String alias) {
        this(className, descriptor, alias, null);
    }

    public SelectItem(String className, String descriptor, String alias, String aggregationFunction) {
        this.className = QueryUtils.stripQuotes(className);
        this.descriptor = (descriptor != null) ? QueryUtils.stripQuotes(descriptor) : null;

        // Generate objectAlias and descriptorAlias when SelectItem is instantiated
        if (aggregationFunction == null) {
            this.alias = !alias.isEmpty() ? alias : (this.descriptor != null ? this.className + "_" + this.descriptor : this.className);
        } else {
            this.alias = !alias.isEmpty() ? alias : aggregationFunction + "_" + className;
        }

        this.objectAlias = !alias.isEmpty() ? alias : this.className;
        this.alias = QueryUtils.sanitizeAlias(this.alias);
        this.objectAlias = QueryUtils.sanitizeAlias(this.objectAlias);

        this.aggregationFunction = aggregationFunction;
        this.variable = (descriptor != null) ? "d_" + this.alias : "o_" + this.alias;
        this.objectVariable = "o_" + this.objectAlias;
    }

    public String getAlias() {
        return alias;
    }

    public String getObjectAlias() {
        return objectAlias;
    }

    public String getVariable() {
        return variable;
    }

    public String getObjectVariable() {
        return objectVariable;
    }

    public String getClassName() {
        return className;
    }

    public String getDescriptor() {
        return descriptor;
    }

    public String getAggregationFunction() {
        return aggregationFunction;
    }

    public void setAggregationFunction(String aggregationFunction) {
        this.aggregationFunction = aggregationFunction;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SelectItem { ");
        sb.append("className='").append(className).append("'");
        if (descriptor != null) {
            sb.append(", descriptor='").append(descriptor).append("'");
        }
        if (aggregationFunction != null) {
            sb.append(", aggregationFunction='").append(aggregationFunction).append("'");
        }
        sb.append(", alias='").append(alias).append("'");
        sb.append(", objectAlias='").append(objectAlias).append("'");
        sb.append(" }");
        return sb.toString();
    }
}
