package deposit.api;

import deposit.service.DeleteUserToolService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class DeleteUserToolAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Delete a User Tool.
	 *
	 * @param userToolName the name of the User Tool to delete.
	 * @param token authentication token.
	 * @throws RuntimeException if there is an error during deletion.
	 */
	@Procedure(name = "deposit.delete.userTool", mode = Mode.WRITE)
	@Description("CALL deposit.delete.userTool(userToolName, token)")
	public void deleteUserTool(
			@Name("userToolName") String userToolName,
			@Name(value = "token", defaultValue = "") String token) {
		DeleteUserToolService service = DeleteUserToolService.getInstance();
		service.deleteUserTool(tx, userToolName, token);
	}
}
