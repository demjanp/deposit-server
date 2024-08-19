package deposit.api;

import deposit.service.GetUserByIdService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetUserByIdAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get an existing User node.
	 *
	 * @param userId the Id of the User node to retrieve.
	 * @param token authentication token.
	 * @return the User node or null if it doesn't exist.
	 * @throws RuntimeException if there was an error retrieving the node.
	 */
	@Procedure(name = "deposit.get.userById", mode = Mode.READ)
	@Description("CALL deposit.get.userById(userId, token) YIELD node AS user")
	public Stream<NodeResult> getUserById(
			@Name("userId") String userId,
			@Name(value = "token", defaultValue = "") String token) {
		GetUserByIdService service = GetUserByIdService.getInstance();
		Node node = service.getUserById(tx, userId, token);
		return node != null ? Stream.of(new NodeResult(node)) : Stream.empty();
	}
}
