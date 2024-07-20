package deposit.api;

import deposit.service.GetActiveNamespaceService;
import deposit.common.result.StringResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.graphdb.Transaction;

import java.util.stream.Stream;

public class GetActiveNamespaceAPI {

	@Context
	public Transaction tx;
	/**
	 * Brief description.
	 *
	 * @return the name of the active Namespace or null if none is set.
	 */
	@Procedure(name = "deposit.get.activeNamespace", mode = Mode.READ)
	@Description("CALL deposit.get.activeNamespace() YIELD value")
	public Stream<StringResult> getActiveNamespace() {
		GetActiveNamespaceService service = GetActiveNamespaceService.getInstance();
		String value = service.getActiveNamespace(tx);
		return Stream.of(new StringResult(value));
	}
}
