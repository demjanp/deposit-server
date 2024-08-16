package deposit.api;

import deposit.service.GetClassService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.stream.Stream;

public class GetClassAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get existing Class nodes.
	 *
	 * @param className name of the Class to retrieve or empty for all Classes.
	 * @param token authentication token.
	 * @return the Class node(s) or null if it doesn't exist.
	 * @throws RuntimeException if there was an error retrieving the node(s).
	 */
	@Procedure(name = "deposit.get.class", mode = Mode.READ)
	@Description("CALL deposit.get.class(className, token) YIELD node AS class")
	public Stream<NodeResult> getClass(
			@Name(value = "className", defaultValue = "") String className,
			@Name(value = "token", defaultValue = "") String token) {
		GetClassService service = GetClassService.getInstance();
		return service.getClass(tx, className, token).map(NodeResult::new);
	}
}
