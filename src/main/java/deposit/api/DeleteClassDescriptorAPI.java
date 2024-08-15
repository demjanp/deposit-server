package deposit.api;

import deposit.service.DeleteClassDescriptorService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class DeleteClassDescriptorAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Delete a Class Descriptor.
	 *
	 * @param className the name of the Class.
	 * @param descriptorName the name of the Class Descriptor.
	 * @param token authentication token.
	 * @throws RuntimeException if the Class Descriptor doesn't exist or there is an error during the deletion.
	 */
	@Procedure(name = "deposit.delete.classDescriptor", mode = Mode.WRITE)
	@Description("CALL deposit.delete.classDescriptor(className, descriptorName, token)")
	public void deleteClassDescriptor(
			@Name("className") String className,
			@Name("descriptorName") String descriptorName,
			@Name(value = "token", defaultValue = "") String token) {
		DeleteClassDescriptorService service = DeleteClassDescriptorService.getInstance();
		service.deleteClassDescriptor(tx, className, descriptorName, token);
	}
}
