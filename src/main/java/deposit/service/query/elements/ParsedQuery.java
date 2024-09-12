package deposit.service.query.elements;

import java.util.ArrayList;
import java.util.List;

public class ParsedQuery {

	private List<SelectItem> selectItems;
	private List<WhereCondition> whereConditions;
	private List<WhereCondition> relationshipConditions;
	private List<String> logicalOperators;

	private boolean distinct;

	public ParsedQuery() {
		this.selectItems = new ArrayList<>();
		this.whereConditions = new ArrayList<>();
		this.relationshipConditions = new ArrayList<>();
		this.logicalOperators = new ArrayList<>();
		this.distinct = false;
	}

	public void addSelectItem(SelectItem selectItem) {
		this.selectItems.add(selectItem);
	}

	public List<SelectItem> getSelectItems() {
		return this.selectItems;
	}

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

	public void addWhereCondition(WhereCondition whereCondition) {
		this.whereConditions.add(whereCondition);
		extractRelationshipConditions(whereCondition);
	}

	private void extractRelationshipConditions(WhereCondition whereCondition) {
		if (whereCondition.getRelationshipType() != null) {
			this.relationshipConditions.add(whereCondition);
		}

		if (whereCondition instanceof CompositeCondition) {
			CompositeCondition compositeCondition = (CompositeCondition) whereCondition;
			for (WhereCondition subCondition : compositeCondition.getConditions()) {
				extractRelationshipConditions(subCondition);
			}
		}
	}

	public List<WhereCondition> getWhereConditions() {
		return this.whereConditions;
	}

	public List<WhereCondition> getRelationshipConditions() {
		return this.relationshipConditions;
	}

	public boolean isDistinct() {
		return distinct;
	}

	public void setDistinct(boolean distinct) {
		this.distinct = distinct;
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("ParsedQuery {\n");

		builder.append("  DISTINCT: ").append(distinct).append("\n");

		builder.append("  Select Items: \n");
		for (SelectItem item : selectItems) {
			builder.append("    ").append(item.toString()).append("\n");
		}

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

		if (!relationshipConditions.isEmpty()) {
			builder.append("  Relationship Conditions: \n");
			for (WhereCondition relationship : relationshipConditions) {
				builder.append("    ").append(relationship.toString()).append("\n");
			}
		} else {
			builder.append("  No Relationship Conditions\n");
		}

		builder.append("}");
		return builder.toString();
	}
}
