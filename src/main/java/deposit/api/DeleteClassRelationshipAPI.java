package deposit.api;

import deposit.service.DeleteClassRelationshipService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class DeleteClassRelationshipAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Delete a Class Relationship.
	 *
	 * @param startClassName the name of the start Class.
	 * @param endClassName the name of the end Class.
	 * @param label the label of the Relationship.
	 * @param token authentication token.
	 * @throws RuntimeException if the Class Relationship doesn't exist or there is an error during the deletion.
	 */
	@Procedure(name = "deposit.delete.classRelationship", mode = Mode.WRITE)
	@Description("CALL deposit.delete.classRelationship(startClassName, endClassName, label, token)")
	public void deleteClassRelationship(
			@Name("startClassName") String startClassName,
			@Name("endClassName") String endClassName,
			@Name("label") String label,
			@Name(value = "token", defaultValue = "") String token) {
		DeleteClassRelationshipService service = DeleteClassRelationshipService.getInstance();
		service.deleteClassRelationship(tx, startClassName, endClassName, label, token);
	}
}
