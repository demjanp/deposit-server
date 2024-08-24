package deposit.api;

import deposit.service.SetPasswordService;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;
import org.neo4j.graphdb.Transaction;

public class SetPasswordAPI extends BaseAPI {

	@Context
	public Transaction tx;

	/**
	 * Set password for a User node.
	 *
	 * @param username the username.
	 * @param password the new password (unhashed).
	 * @param token authentication token.
	 * @throws RuntimeException if the User node couldn't be updated.
	 */
	@Procedure(name = "deposit.set.password", mode = Mode.WRITE)
	@Description("CALL deposit.set.password(username, password, token)")
	public void setPassword(
			@Name("username") String username,
			@Name("password") String password,
			@Name(value = "token", defaultValue = "") String token) {
		SetPasswordService service = SetPasswordService.getInstance();
		service.setPassword(tx, username, password, token);
	}
}
