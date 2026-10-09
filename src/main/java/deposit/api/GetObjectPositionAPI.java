package deposit.api;

import deposit.service.GetObjectPositionService;
import deposit.common.result.PosResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.stream.Stream;

public class GetObjectPositionAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get the position of an Object node in the graph.
	 *
	 * @param objectId Id of the Object to retrieve.
	 * @param token authentication token.
	 * @return the position.
	 * @throws RuntimeException if retrieving the position failed.
	 */
	@Procedure(name = "deposit.get.objectPosition", mode = Mode.READ)
	@Description("CALL deposit.get.objectPosition(objectId, token) YIELD x, y")
	public Stream<PosResult> getObjectPosition(
			@Name("objectId") String objectId,
			@Name(value = "token", defaultValue = "") String token) {
		GetObjectPositionService service = GetObjectPositionService.getInstance();
		double[] position = service.getObjectPosition(tx, objectId, token);
		return position.length == 2 ? Stream.of(new PosResult(position[0], position[1])) : Stream.empty();
	}
}
