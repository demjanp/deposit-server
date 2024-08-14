package deposit.api;

import deposit.service.DeleteClassRelationshipByIdService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class DeleteClassRelationshipByIdAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Delete a Class Relationship.
	 *
	 * @param relationshipId the Id of the Class Relationship.
	 * @param token authentication token.
	 * @throws RuntimeException if the Relationship doesn't exist or there is an error during the deletion.
	 */
	@Procedure(name = "deposit.delete.classRelationshipById", mode = Mode.WRITE)
	@Description("CALL deposit.delete.classRelationshipById(relationshipId, token)")
	public void deleteClassRelationshipById(
			@Name("relationshipId") String relationshipId,
			@Name(value = "token", defaultValue = "") String token) {
		DeleteClassRelationshipByIdService service = DeleteClassRelationshipByIdService.getInstance();
		service.deleteClassRelationshipById(tx, relationshipId, token);
	}
}
