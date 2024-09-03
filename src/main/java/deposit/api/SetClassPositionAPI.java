package deposit.api;

import deposit.service.SetClassPositionService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.List;

public class SetClassPositionAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Set the position of a Class node in the graph.
	 *
	 * @param className the name of the Class.
	 * @param position position of the node in a hierarchical graph layout defined as [x, y] or [] to unset position.
	 * @param token authentication token.
	 * @throws RuntimeException if setting the position failed.
	 */
	@Procedure(name = "deposit.set.classPosition", mode = Mode.WRITE)
	@Description("CALL deposit.set.classPosition(className, position, token)")
	public void setClassPosition(
			@Name("className") String className,
			@Name("position") List<Double> position,
			@Name(value = "token", defaultValue = "") String token) {
		SetClassPositionService service = SetClassPositionService.getInstance();
		service.setClassPosition(tx, className, position, token);
	}
}
