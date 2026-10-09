package deposit.service.query.elements;

import deposit.service.query.QueryUtils;

public class SelectItem {
	private String className;
	private String descriptor;
	private String alias;
	private String aggregationFunction;
	private Double functionValue;
	private boolean distinct;
	private String variable;
	private String objectVariable;
	private String resultKey;

	public SelectItem(String className, String descriptor, String alias) {
		this(className, descriptor, alias, null, null, false);
	}

	public SelectItem(String className, String descriptor, String alias, String aggregationFunction, Double functionValue, boolean distinct) {
		this.className = QueryUtils.stripQuotes(className);
		this.descriptor = (descriptor != null) ? QueryUtils.stripQuotes(descriptor) : null;
		
		if (aggregationFunction == null) {
			this.alias = !alias.isEmpty() ? alias : (this.descriptor != null ? this.className + "_" + this.descriptor : this.className);
			this.resultKey = !alias.isEmpty() ? alias : (this.descriptor != null ? this.className + "." + this.descriptor : this.className);
		} else {
			this.alias = !alias.isEmpty() ? alias : (this.descriptor != null ? aggregationFunction + "_" + className + "_" + descriptor : aggregationFunction + "_" + className);
			this.resultKey = this.alias;
		}
		
		this.alias = QueryUtils.sanitizeAlias(this.alias);
		this.aggregationFunction = aggregationFunction;
		this.variable = (descriptor != null) ? "d_" + this.alias : "o_" + this.alias;
		this.objectVariable = "o_" + (!alias.isEmpty() ? alias : QueryUtils.sanitizeAlias(this.className));
		this.functionValue = functionValue;
		this.distinct = distinct;
	}

	public String getAlias() {
		return alias;
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

	public boolean isAggregation() {
		return aggregationFunction != null && !aggregationFunction.isEmpty();
	}

	public boolean isDistinct() { return distinct; }

	public Double getFunctionValue() {
		return functionValue;
	}
	
	public String getResultKey() {
		return resultKey;
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
		sb.append(" }");
		return sb.toString();
	}
}
