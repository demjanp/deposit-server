package deposit.api;

import deposit.service.GetDataService;
import deposit.common.result.MapResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.List;
import java.util.stream.Stream;

public class GetDataAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get nodes, relationships and resource filenames for the subtrees of the root Objects or the whole Namespace.
	 *
	 * @param rootIds a list of root Object ids or empty for the whole Namespace. Default is [].
	 * @param token authentication token.
	 * @return a Map in the format:  {nodes: nodes, relationships: relationships, resources: [<local filename of the resource>, ...]}
	 * @throws RuntimeException if there is an error retrieving the data.
	 */
	@Procedure(name = "deposit.get.data", mode = Mode.READ)
	@Description("CALL deposit.get.data(rootIds, token) YIELD data")
	public Stream<MapResult> getData(
			@Name(value = "rootIds", defaultValue = "[]") List<String> rootIds,
			@Name(value = "token", defaultValue = "") String token) {
		if (rootIds == null) {
			rootIds = List.of();
		}
		GetDataService service = GetDataService.getInstance();
		MapResult data = service.getData(tx, rootIds, token);
		return Stream.of(data);
	}
}
