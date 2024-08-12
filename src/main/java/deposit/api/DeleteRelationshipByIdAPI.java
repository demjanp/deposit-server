package deposit.api;

import deposit.service.DeleteRelationshipByIdService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class DeleteRelationshipByIdAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Delete a Relationship.
	 *
	 * @param relationshipId the Id of the Relationship.
	 * @param token authentication token.
	 * @throws RuntimeException if the Relationship doesn't exist or there is an error during the deletion.
	 */
	@Procedure(name = "deposit.delete.relationshipById", mode = Mode.WRITE)
	@Description("CALL deposit.delete.relationshipById(relationshipId, token)")
	public void deleteRelationshipById(
			@Name("relationshipId") String relationshipId,
			@Name(value = "token", defaultValue = "") String token) {
		DeleteRelationshipByIdService service = DeleteRelationshipByIdService.getInstance();
		service.deleteRelationshipById(tx, relationshipId, token);
	}
}
