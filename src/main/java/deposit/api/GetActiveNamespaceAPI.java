package deposit.api;

import deposit.service.GetActiveNamespaceService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetActiveNamespaceAPI {

	@Context
	public Transaction tx;
	/**
	 * Brief description.
	 *
	 * @return the active Namespace node or null if none is set.
	 */
	@Procedure(name = "deposit.get.activeNamespace", mode = Mode.READ)
	@Description("CALL deposit.get.activeNamespace() YIELD node AS namespace")
	public Stream<NodeResult> getActiveNamespace() {
		GetActiveNamespaceService service = GetActiveNamespaceService.getInstance();
		Node node = service.getActiveNamespace(tx);
		return Stream.of(new NodeResult(node));
	}
}
