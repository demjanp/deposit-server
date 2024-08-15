package deposit.api;

import deposit.service.DeleteClassDescriptorByIdService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class DeleteClassDescriptorByIdAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Delete a Class Descriptor.
	 *
	 * @param descriptorId the Id of the Class Descriptor.
	 * @param token authentication token.
	 * @throws RuntimeException if the Class Descriptor doesn't exist or there is an error during the deletion.
	 */
	@Procedure(name = "deposit.delete.classDescriptorById", mode = Mode.WRITE)
	@Description("CALL deposit.delete.classDescriptorById(descriptorId, token)")
	public void deleteClassDescriptorById(
			@Name("descriptorId") String descriptorId,
			@Name(value = "token", defaultValue = "") String token) {
		DeleteClassDescriptorByIdService service = DeleteClassDescriptorByIdService.getInstance();
		service.deleteClassDescriptorById(tx, descriptorId, token);
	}
}
