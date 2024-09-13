package deposit.service.query.translators;

import deposit.service.query.elements.ParsedQuery;
import deposit.service.query.elements.SelectItem;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.ArrayList;

public class ReturnClauseBuilder {
	private static final DecimalFormat decimalFormat = createDecimalFormat();

	private static DecimalFormat createDecimalFormat() {
		DecimalFormatSymbols symbols = new DecimalFormatSymbols();
		symbols.setDecimalSeparator('.');
		DecimalFormat format = new DecimalFormat();
		format.setDecimalFormatSymbols(symbols);
		return format;
	}

	public static ReturnResult buildReturnClause(ParsedQuery parsedQuery) {
		StringBuilder returnClause = new StringBuilder();
		List<String> returnedNodes = new ArrayList<>();
		List<String> returnedKeys = new ArrayList<>();

		if (parsedQuery.isDistinct()) {
			returnClause.append("DISTINCT ");
		}

		List<SelectItem> selectItems = parsedQuery.getSelectItems();
		for (int i = 0; i < selectItems.size(); i++) {
			SelectItem selectItem = selectItems.get(i);
			if (i > 0) {
				returnClause.append(", ");
			}
			if (selectItem.isAggregation()) {
				appendAggregationFunction(returnClause, selectItem);
			} else {
				appendRegularItem(returnClause, selectItem);
			}
			returnedNodes.add(selectItem.getAlias());
			returnedKeys.add(selectItem.getResultKey());
		}

		return new ReturnResult(returnClause.toString(), returnedNodes, returnedKeys);
	}

	public static class ReturnResult {
		private final String returnClause;
		private final List<String> returnedNodes;
		private final List<String> returnedKeys;

		public ReturnResult(String returnClause, List<String> returnedNodes, List<String> returnedKeys) {
			this.returnClause = returnClause;
			this.returnedNodes = returnedNodes;
			this.returnedKeys = returnedKeys;
		}

		public String getReturnClause() {
			return returnClause;
		}

		public List<String> getReturnedNodes() {
			return returnedNodes;
		}
		
		public List<String> getReturnedKeys() {
			return returnedKeys;
		}
	}

	private static void appendAggregationFunction(StringBuilder returnClause, SelectItem selectItem) {
		String distinctClause = selectItem.isDistinct() ? "DISTINCT " : "";
		String function = selectItem.getAggregationFunction().toUpperCase();
		switch (function) {
			case "PERCENTILE":
				appendPercentileFunction(returnClause, selectItem, distinctClause);
				break;
			case "COUNT":
				appendCountFunction(returnClause, selectItem, distinctClause);
				break;
			default:
				appendGenericFunction(returnClause, selectItem, distinctClause, function);
				break;
		}
	}

	private static void appendPercentileFunction(StringBuilder returnClause, SelectItem selectItem, String distinctClause) {
		if (selectItem.getFunctionValue() != null) {
			String formattedPercentile = decimalFormat.format(selectItem.getFunctionValue());
			returnClause.append(String.format("percentileCont(%s%s.value, %s) AS %s",
				distinctClause, selectItem.getVariable(), formattedPercentile, selectItem.getAlias()));
		}
	}

	private static void appendCountFunction(StringBuilder returnClause, SelectItem selectItem, String distinctClause) {
		returnClause.append(String.format("COUNT(%s%s) AS %s",
			distinctClause, selectItem.getObjectVariable(), selectItem.getAlias()));
	}

	private static void appendGenericFunction(StringBuilder returnClause, SelectItem selectItem, String distinctClause, String function) {
		returnClause.append(String.format("%s(%s%s.value) AS %s",
			function, distinctClause, selectItem.getVariable(), selectItem.getAlias()));
	}

	private static void appendRegularItem(StringBuilder returnClause, SelectItem selectItem) {
		String variable = selectItem.getDescriptor() != null && !selectItem.getDescriptor().isEmpty()
				? selectItem.getVariable()
				: selectItem.getObjectVariable();
		returnClause.append(String.format("%s AS %s", variable, selectItem.getAlias()));
	}
}
