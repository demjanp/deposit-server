package deposit.api;

import deposit.service.SetObjectPositionService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.List;

public class SetObjectPositionAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Set the position of an Object node in the graph.
	 *
	 * @param objectId Id of the Object to update.
	 * @param position position of the node in a hierarchical graph layout defined as [x, y] or [] to unset position.
	 * @param token authentication token.
	 * @throws RuntimeException if setting the position failed.
	 */
	@Procedure(name = "deposit.set.objectPosition", mode = Mode.WRITE)
	@Description("CALL deposit.set.objectPosition(objectId, position, token)")
	public void setObjectPosition(
			@Name("objectId") String objectId,
			@Name("position") List<Double> position,
			@Name(value = "token", defaultValue = "") String token) {
		SetObjectPositionService service = SetObjectPositionService.getInstance();
		service.setObjectPosition(tx, objectId, position, token);
	}
}
