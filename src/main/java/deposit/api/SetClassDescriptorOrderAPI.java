package deposit.api;

import deposit.service.SetClassDescriptorOrderService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class SetClassDescriptorOrderAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Set order of a Class Descriptor.
	 *
	 * @param className the name of the Class.
	 * @param descriptorName the name of the Descriptor.
	 * @param order an order of the Class when represented in a list, -1 indicates the end of the list.
	 * @param token authentication token.
	 * @throws RuntimeException if there is an error setting the order.
	 */
	@Procedure(name = "deposit.set.classDescriptorOrder", mode = Mode.WRITE)
	@Description("CALL deposit.set.classDescriptorOrder(className, descriptorName, order, token)")
	public void setClassDescriptorOrder(
			@Name("className") String className,
			@Name("descriptorName") String descriptorName,
			@Name("order") Long order,
			@Name(value = "token", defaultValue = "") String token) {
		SetClassDescriptorOrderService service = SetClassDescriptorOrderService.getInstance();
		service.setClassDescriptorOrder(tx, className, descriptorName, order, token);
	}
}
