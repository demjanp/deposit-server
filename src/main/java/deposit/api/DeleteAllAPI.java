package deposit.api;

import deposit.service.DeleteAllService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class DeleteAllAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Delete all nodes from the active Namespace.
	 *
	 * @param token authentication token.
	 * @throws RuntimeException if there is an error during deletion.
	 */
	@Procedure(name = "deposit.delete.all", mode = Mode.WRITE)
	@Description("CALL deposit.delete.all(token)")
	public void deleteAll(
			@Name(value = "token", defaultValue = "") String token) {
		DeleteAllService service = DeleteAllService.getInstance();
		service.deleteAll(tx, token);
	}
}
