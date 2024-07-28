package deposit.api;

import deposit.service.GetNamespaceService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.stream.Stream;

public class GetNamespaceAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Brief description.
	 *
	 * @param namespaceName name of the Namespace to retrieve or empty for all namespaces.
	 * @param token authentication token.
	 * @return the Namespace node(s) or null if it doesn't exist.
	 * @throws RuntimeException if there was an error retrieving the node(s).
	 */
	@Procedure(name = "deposit.get.namespace", mode = Mode.WRITE)
	@Description("CALL deposit.get.namespace(namespaceName, token) YIELD node AS namespace")
	public Stream<NodeResult> getNamespace(
			@Name(value = "namespaceName", defaultValue = "") String namespaceName,
			@Name(value = "token", defaultValue = "") String token) {
		GetNamespaceService service = GetNamespaceService.getInstance();
		return service.getNamespace(tx, namespaceName, token).map(NodeResult::new);
	}
}
