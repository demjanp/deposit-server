package deposit.api;

import deposit.service.DeleteClassService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class DeleteClassAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Delete a Class.
	 *
	 * @param className the name of the Class to delete.
	 * @param token authentication token.
	 * @throws RuntimeException if there is an error during deletion.
	 */
	@Procedure(name = "deposit.delete.class", mode = Mode.WRITE)
	@Description("CALL deposit.delete.class(className, token)")
	public void deleteClass(
			@Name("className") String className,
			@Name(value = "token", defaultValue = "") String token) {
		DeleteClassService service = DeleteClassService.getInstance();
		service.deleteClass(tx, className, token);
	}
}
