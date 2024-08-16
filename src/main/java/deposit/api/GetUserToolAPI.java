package deposit.api;

import deposit.service.GetUserToolService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetUserToolAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get existing User Tool nodes.
	 *
	 * @param userToolName name of the User Tool to retrieve or empty for all User Tools.
	 * @param token authentication token.
	 * @return the User Tool node(s) or null if it doesn't exist.
	 * @throws RuntimeException if there was an error retrieving the node(s).
	 */
	@Procedure(name = "deposit.get.userTool", mode = Mode.READ)
	@Description("CALL deposit.get.userTool(userToolName, token) YIELD node AS userTool")
	public Stream<NodeResult> getUserTool(
			@Name(value = "userToolName", defaultValue = "") String userToolName,
			@Name(value = "token", defaultValue = "") String token) {
		GetUserToolService service = GetUserToolService.getInstance();
		return service.getUserTool(tx, userToolName, token).map(NodeResult::new);
	}
}
