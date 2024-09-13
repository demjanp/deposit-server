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
            return "";  // No ORDER BY clause needed
        }

        List<String> orderByPatterns = new ArrayList<>();

        for (OrderByItem orderByItem : orderByItems) {
            orderByPatterns.add(generateOrderByPattern(parsedQuery, orderByItem));
        }

        return String.join(", ", orderByPatterns);
    }

    private static String generateOrderByPattern(ParsedQuery parsedQuery, OrderByItem orderByItem) {
        String alias = orderByItem.getSelectItem().getAlias();
        SelectItem selectItem = parsedQuery.getSelectItemByAlias(alias);
        if (selectItem == null) {
            throw new IllegalStateException("ORDER BY item not defined in SELECT statement: " + alias);
        }
        String value = selectItem.getDescriptor() != null ? ".value" : "";
        return selectItem.getVariable() + value + " " + orderByItem.getOrderDirection();
    }
}
