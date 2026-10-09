package deposit.api;

import deposit.service.SetClassOrderService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class SetClassOrderAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Set order of a Class.
	 *
	 * @param className the name of the Class.
	 * @param order an order of the Class when represented in a list, -1 indicates the end of the list.
	 * @param token authentication token.
	 * @throws RuntimeException if there is an error setting the order.
	 */
	@Procedure(name = "deposit.set.classOrder", mode = Mode.WRITE)
	@Description("CALL deposit.set.classOrder(className, order, token)")
	public void setClassOrder(
			@Name("className") String className,
			@Name("order") Long order,
			@Name(value = "token", defaultValue = "") String token) {
		SetClassOrderService service = SetClassOrderService.getInstance();
		service.setClassOrder(tx, className, order, token);
	}
}
