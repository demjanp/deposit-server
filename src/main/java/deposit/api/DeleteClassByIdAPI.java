package deposit.api;

import deposit.service.DeleteClassByIdService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class DeleteClassByIdAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Delete a Class.
	 *
	 * @param classId the Id of the Class.
	 * @param token authentication token.
	 * @throws RuntimeException if the Class doesn't exist or there is an error during the deletion.
	 */
	@Procedure(name = "deposit.delete.classById", mode = Mode.WRITE)
	@Description("CALL deposit.delete.classById(classId, token)")
	public void deleteClassById(
			@Name("classId") String classId,
			@Name(value = "token", defaultValue = "") String token) {
		DeleteClassByIdService service = DeleteClassByIdService.getInstance();
		service.deleteClassById(tx, classId, token);
	}
}
