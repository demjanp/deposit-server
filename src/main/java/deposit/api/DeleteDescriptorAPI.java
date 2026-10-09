package deposit.api;

import deposit.service.DeleteDescriptorService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class DeleteDescriptorAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Delete a Descriptor.
	 *
	 * @param objectId the Id of the Object.
	 * @param descriptorName the name of the Descriptor.
	 * @param token authentication token.
	 * @throws RuntimeException if the Descriptor doesn't exist or there is an error during the deletion.
	 */
	@Procedure(name = "deposit.delete.descriptor", mode = Mode.WRITE)
	@Description("CALL deposit.delete.descriptor(objectId, descriptorName, token)")
	public void deleteDescriptor(
			@Name("objectId") String objectId,
			@Name("descriptorName") String descriptorName,
			@Name(value = "token", defaultValue = "") String token) {
		DeleteDescriptorService service = DeleteDescriptorService.getInstance();
		service.deleteDescriptor(tx, objectId, descriptorName, token);
	}
}
