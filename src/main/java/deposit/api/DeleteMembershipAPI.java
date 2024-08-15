package deposit.api;

import deposit.service.DeleteMembershipService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class DeleteMembershipAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Delete a Class Membership.
	 *
	 * @param className the name of the Class.
	 * @param objectId the Id of the Object.
	 * @param token authentication token.
	 * @throws RuntimeException if the Membership doesn't exist or if there is an error during the deletion.
	 */
	@Procedure(name = "deposit.delete.membership", mode = Mode.WRITE)
	@Description("CALL deposit.delete.membership(className, objectId, token)")
	public void deleteMembership(
			@Name("className") String className,
			@Name("objectId") String objectId,
			@Name(value = "token", defaultValue = "") String token) {
		DeleteMembershipService service = DeleteMembershipService.getInstance();
		service.deleteMembership(tx, className, objectId, token);
	}
}
