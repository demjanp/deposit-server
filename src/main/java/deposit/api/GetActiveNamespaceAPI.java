package deposit.api;

import deposit.service.GetActiveNamespaceService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetActiveNamespaceAPI extends BaseAPI {
	
	@Context
	public Transaction tx;
	
	/**
	 * Returns the active Namespace node.
	 *
	 * @param token authentication token.
	 * @return the active Namespace node or null if none is set.
	 */
	@Procedure(name = "deposit.get.activeNamespace", mode = Mode.READ)
	@Description("CALL deposit.get.activeNamespace(token) YIELD node AS namespace")
	public Stream<NodeResult> getActiveNamespace(
			@Name(value = "token", defaultValue = "") String token) {
		GetActiveNamespaceService service = GetActiveNamespaceService.getInstance();
		Node node = service.getActiveNamespace(tx, token);
		return Stream.of(new NodeResult(node));
	}
}
