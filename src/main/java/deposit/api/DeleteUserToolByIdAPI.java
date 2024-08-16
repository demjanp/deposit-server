package deposit.api;

import deposit.service.DeleteUserToolByIdService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class DeleteUserToolByIdAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Delete a User Tool.
	 *
	 * @param userToolId Id of the User Tool to delete.
	 * @param token authentication token.
	 * @throws RuntimeException if there is an error during deletion.
	 */
	@Procedure(name = "deposit.delete.userToolById", mode = Mode.WRITE)
	@Description("CALL deposit.delete.userToolById(userToolId, token)")
	public void deleteUserToolById(
			@Name("userToolId") String userToolId,
			@Name(value = "token", defaultValue = "") String token) {
		DeleteUserToolByIdService service = DeleteUserToolByIdService.getInstance();
		service.deleteUserToolById(tx, userToolId, token);
	}
}
