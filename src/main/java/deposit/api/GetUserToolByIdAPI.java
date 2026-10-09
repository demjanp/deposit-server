package deposit.api;

import deposit.service.GetUserToolByIdService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetUserToolByIdAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get an existing User Tool node.
	 *
	 * @param userToolId Id of the User Tool to retrieve.
	 * @param token authentication token.
	 * @return the User Tool node or null if it doesn't exist.
	 * @throws RuntimeException if there was an error retrieving the node.
	 */
	@Procedure(name = "deposit.get.userToolById", mode = Mode.READ)
	@Description("CALL deposit.get.userToolById(userToolId, token) YIELD node AS userTool")
	public Stream<NodeResult> getUserToolById(
			@Name("userToolId") String userToolId,
			@Name(value = "token", defaultValue = "") String token) {
		GetUserToolByIdService service = GetUserToolByIdService.getInstance();
		Node node = service.getUserToolById(tx, userToolId, token);
		return node != null ? Stream.of(new NodeResult(node)) : Stream.empty();
	}
}
