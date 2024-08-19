package deposit.api;

import deposit.service.GetUserService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.stream.Stream;

public class GetUserAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get existing User node(s).
	 *
	 * @param username the username of the User to retrieve or empty for all User nodes.
	 * @param token authentication token.
	 * @return the User node(s) or null if it doesn't exist.
	 * @throws RuntimeException if there was an error retrieving the node(s).
	 */
	@Procedure(name = "deposit.get.user", mode = Mode.READ)
	@Description("CALL deposit.get.user(username, token) YIELD node AS user")
	public Stream<NodeResult> getUser(
			@Name(value = "username", defaultValue = "") String username,
			@Name(value = "token", defaultValue = "") String token) {
		GetUserService service = GetUserService.getInstance();
		return service.getUser(tx, username, token).map(NodeResult::new);
	}
}
