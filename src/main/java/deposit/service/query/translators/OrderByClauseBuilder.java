package deposit.service.query.translators;

import deposit.service.query.elements.OrderByItem;
import deposit.service.query.elements.ParsedQuery;
import deposit.service.query.elements.SelectItem;

import java.util.ArrayList;
import java.util.List;

public class OrderByClauseBuilder {
	
	public static String buildOrderByClause(ParsedQuery parsedQuery) {
		List<OrderByItem> orderByItems = parsedQuery.getOrderByItems();
		if (orderByItems.isEmpty()) {
			return "";
		}
		List<String> orderByPatterns = new ArrayList<>();
		for (OrderByItem orderByItem : orderByItems) {
			orderByPatterns.add(generateOrderByPattern(parsedQuery, orderByItem));
		}
		return String.join(", ", orderByPatterns);
	}

	private static String generateOrderByPattern(ParsedQuery parsedQuery, OrderByItem orderByItem) {
		String alias = orderByItem.getSelectItem().getAlias();
		if (orderByItem.getSelectItem().getDescriptor() == null && !orderByItem.getSelectItem().isAggregation()) {
			alias = String.format("ID(%s)", alias);
		}
		return alias + " " + orderByItem.getOrderDirection();
	}
}
