package deposit.api;

import deposit.service.ImportDataService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class ImportDataAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Import GraphSON data into the database.
	 *
	 * @param data Map in the GraphSON format: {nodes: nodes, edges: edges, resources: resources}, where nodes = [{id, label, properties: {name: value, ...}}, ...], edges = [{id, type, outV: start_node_id, inV: end_node_id, properties: {name: value, ...}}, ...], resources: [<local filename of the resource>, ...]
	 * @param unique List of Class names where new Objects should always be added, rather than reusing existing Objects with identical Descriptors. This ensures unique Objects for these Classes.
	 * @param overwrite List of ids of Objects existing both in the data and the current Namespace where existing Descriptors should be deleted and new ones created.
	 * @param matchEmpty boolean, if true, ensure all Descriptors of the matched Objects are present and equal those in data. Otherwise only match Descriptors present in the Namespace and with non-null values in the data.
	 * @param token authentication token.
	 * @throws RuntimeException if there was an error importing the data.
	 */
	@Procedure(name = "deposit.import.data", mode = Mode.WRITE)
	@Description("CALL deposit.import.data(data, unique, overwrite, matchEmpty, token)")
	public void importData(
			@Name("data") Map<String, Object> data,
			@Name("unique") List<String> unique,
			@Name("overwrite") List<String> overwrite,
			@Name("matchEmpty") boolean matchEmpty,
			@Name(value = "token", defaultValue = "") String token) {
		ImportDataService service = ImportDataService.getInstance();
		service.importData(tx, data, unique, overwrite, matchEmpty, token);
	}
}
