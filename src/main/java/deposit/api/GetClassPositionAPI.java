package deposit.api;

import deposit.service.GetClassPositionService;
import deposit.common.result.PosResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.stream.Stream;

public class GetClassPositionAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get the position of a Class node in the graph.
	 *
	 * @param className the name of the Class.
	 * @param token authentication token.
	 * @return the position.
	 * @throws RuntimeException if retrieving the position failed.
	 */
	@Procedure(name = "deposit.get.classPosition", mode = Mode.READ)
	@Description("CALL deposit.get.classPosition(className, token) YIELD x, y")
	public Stream<PosResult> getClassPosition(
			@Name("className") String className,
			@Name(value = "token", defaultValue = "") String token) {
		GetClassPositionService service = GetClassPositionService.getInstance();
		double[] position = service.getClassPosition(tx, className, token);
		return position.length == 2 ? Stream.of(new PosResult(position[0], position[1])) : Stream.empty();
	}
}
