package deposit.api;

import deposit.service.GetObjectsByDataService;
import deposit.common.result.StringMapResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.Map;
import java.util.stream.Stream;

public class GetObjectsByDataAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Try to retrieve Objects from an identical data structure in the active Namespace as the one supplied as GraphSON data.
	 *
	 * @param data Map in the GraphSON format: {nodes: nodes, edges: edges}, where nodes = [{id, label, properties: {name: value, ...}}, ...], edges = [{id, type, outV: start_node_id, inV: end_node_id, properties: {name: value, ...}}, ...]
	 * @param matchEmpty boolean, if true, ensure all Descriptors of the matched Objects are present and equal those in data. Otherwise only match Descriptors present in the Namespace and with non-null values in the data.
	 * @param token authentication token.
	 * @return a map of the original Object ids and the ids of the matched Objects in the current Namespace
	 * @throws RuntimeException if there was an error retrieving the nodes.
	 */
	@Procedure(name = "deposit.get.objectsByData", mode = Mode.READ)
	@Description("CALL deposit.get.objectsByData(data, matchEmpty, token) YIELD data")
	public Stream<StringMapResult> getObjectsByData(
			@Name("data") Map<String, Object> data,
			@Name("matchEmpty") boolean matchEmpty,
			@Name(value = "token", defaultValue = "") String token) {
		GetObjectsByDataService service = GetObjectsByDataService.getInstance();
		StringMapResult objectMap = service.getObjectsByData(tx, data, matchEmpty, token);
		return Stream.of(objectMap);
	}
}
