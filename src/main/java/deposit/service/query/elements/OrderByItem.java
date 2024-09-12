package deposit.service.query.elements;

public class OrderByItem {
	private SelectItem selectItem;
	private String orderDirection;

	public OrderByItem(SelectItem selectItem, String orderDirection) {
		this.selectItem = selectItem;
		this.orderDirection = orderDirection != null ? orderDirection.toUpperCase() : "ASC";
	}

	public SelectItem getSelectItem() {
		return selectItem;
	}

	public String getOrderDirection() {
		return orderDirection;
	}

	@Override
	public String toString() {
		return "OrderByItem { " +
				"selectItem=" + selectItem +
				", orderDirection='" + orderDirection + '\'' +
				" }";
	}
}
