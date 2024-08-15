package deposit.api;

import deposit.service.DeleteRelationshipService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class DeleteRelationshipAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Delete a Relationship.
	 *
	 * @param startObjectId the Id of the start Object.
	 * @param endObjectId the Id of the end Object.
	 * @param label the label of the Relationship.
	 * @param token authentication token.
	 * @throws RuntimeException if the Relationship doesn't exist or there is an error during the deletion.
	 */
	@Procedure(name = "deposit.delete.relationship", mode = Mode.WRITE)
	@Description("CALL deposit.delete.relationship(startObjectId, endObjectId, label)")
	public void deleteRelationship(
			@Name("startObjectId") String startObjectId,
			@Name("endObjectId") String endObjectId,
			@Name("label") String label,
			@Name(value = "token", defaultValue = "") String token) {
		DeleteRelationshipService service = DeleteRelationshipService.getInstance();
		service.deleteRelationship(tx, startObjectId, endObjectId, label, token);
	}
}
