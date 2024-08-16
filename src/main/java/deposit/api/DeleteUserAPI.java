package deposit.api;

import deposit.service.DeleteUserService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class DeleteUserAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Delete a User node.
	 *
	 * @param username the username of the User to delete.
	 * @param token authentication token.
	 * @throws RuntimeException if there is an error during deletion
	 */
	@Procedure(name = "deposit.delete.user", mode = Mode.WRITE)
	@Description("CALL deposit.delete.user(username, token)")
	public void deleteUser(
			@Name("username") String username,
			@Name(value = "token", defaultValue = "") String token) {
		DeleteUserService service = DeleteUserService.getInstance();
		service.deleteUser(tx, username, token);
	}
}
