package deposit.api;

import deposit.service.DeleteDescriptorByIdService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class DeleteDescriptorByIdAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Delete a Descriptor.
	 *
	 * @param descriptorId the Id of the Descriptor.
	 * @param token authentication token.
	 * @throws RuntimeException if the Descriptor doesn't exist or there is an error during the deletion.
	 */
	@Procedure(name = "deposit.delete.descriptorById", mode = Mode.WRITE)
	@Description("CALL deposit.delete.descriptorById(descriptorId, token)")
	public void deleteDescriptorById(
			@Name("descriptorId") String descriptorId,
			@Name(value = "token", defaultValue = "") String token) {
		DeleteDescriptorByIdService service = DeleteDescriptorByIdService.getInstance();
		service.deleteDescriptorById(tx, descriptorId, token);
	}
}
