package deposit.api;

import deposit.service.DeleteObjectService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class DeleteObjectAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Delete an Object.
	 *
	 * @param objectId the Id of the Object.
	 * @param token authentication token.
	 * @throws RuntimeException if the Object doesn't exist or there is an error during the deletion.
	 */
	@Procedure(name = "deposit.delete.object", mode = Mode.WRITE)
	@Description("CALL deposit.delete.object(objectId, token)")
	public void deleteObject(
			@Name("objectId") String objectId,
			@Name(value = "token", defaultValue = "") String token) {
		DeleteObjectService service = DeleteObjectService.getInstance();
		service.deleteObject(tx, objectId, token);
	}
}
