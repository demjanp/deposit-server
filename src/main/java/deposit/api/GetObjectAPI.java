package deposit.api;

import deposit.service.GetObjectService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.List;
import java.util.stream.Stream;

public class GetObjectAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get existing Object nodes which match the parameters (all if no parameters are given).
	 *
	 * @param className a list of Class names, default is [].
	 * @param descriptors Descriptors of the Object defined as a list of lists [[<name>, <value>], ...], where <name> is the name of the Descriptor, <value> is any Neo4j type. Default is [].
	 * @param token authentication token.
	 * @return the Object node(s) or null if it doesn't exist.
	 * @throws RuntimeException if there was an error retrieving the node(s).
	 */
	@Procedure(name = "deposit.get.object", mode = Mode.READ)
	@Description("CALL deposit.get.object(className, descriptors, token) YIELD node AS object")
	public Stream<NodeResult> getObject(
			@Name(value = "className", defaultValue = "") String className,
			@Name(value = "descriptors", defaultValue = "[]") List<List<Object>> descriptors,
			@Name(value = "token", defaultValue = "") String token) {
		GetObjectService service = GetObjectService.getInstance();
		return service.getObject(tx, className, descriptors, token).map(NodeResult::new);
	}
}
