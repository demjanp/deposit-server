package deposit.api;

import deposit.service.GetNamespaceDataService;
import deposit.common.result.MapResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.stream.Stream;

public class GetNamespaceDataAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get nodes, Relationships and resource filenames for the whole Namespace.
	 *
	 * @param token authentication token.
	 * @return a Map in the format:  {graph: {nodes: nodes, edges: edges}, resources: [<local filename of the resource>, ...]} where graph is in GraphSON format
	 * @throws RuntimeException if there is an error retrieving the data.
	 */
	@Procedure(name = "deposit.get.namespaceData", mode = Mode.READ)
	@Description("CALL deposit.get.namespaceData(token) YIELD data")
	public Stream<MapResult> getNamespaceData(
			@Name(value = "token", defaultValue = "") String token) {
		GetNamespaceDataService service = GetNamespaceDataService.getInstance();
		MapResult data = service.getNamespaceData(tx, token);
		return Stream.of(data);
	}
}
