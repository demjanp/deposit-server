package deposit.service.query.translators;

import deposit.service.query.elements.ParsedQuery;
import deposit.service.query.elements.SelectItem;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;

public class ReturnClauseBuilder {

	private static final DecimalFormat decimalFormat = createDecimalFormat();

	private static DecimalFormat createDecimalFormat() {
		DecimalFormatSymbols symbols = new DecimalFormatSymbols();
		symbols.setDecimalSeparator('.');  // Force dot (.) as the decimal separator
		DecimalFormat format = new DecimalFormat();
		format.setDecimalFormatSymbols(symbols);
		return format;
	}

	public static String buildReturnClause(ParsedQuery parsedQuery) {
		StringBuilder returnClause = new StringBuilder();

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
		}

		return returnClause.toString();
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
		returnClause.append(String.format("%s(%s%s) AS %s",
			"COUNT", distinctClause, selectItem.getObjectVariable(), selectItem.getAlias()));
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
