package deposit.api;

import deposit.service.SetRelationshipWeightService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class SetRelationshipWeightAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Update the weight of a Relationship.
	 *
	 * @param startObjectId the Id of the start Object.
	 * @param endObjectId the Id of the end Object.
	 * @param label the label of the Relationship.
	 * @param weight the weight of the Relationship.
	 * @param token authentication token.
	 * @throws RuntimeException if there is an error setting the weight.
	 */
	@Procedure(name = "deposit.set.relationshipWeight", mode = Mode.WRITE)
	@Description("CALL deposit.set.relationshipWeight(startObjectId, endObjectId, label, weight, token)")
	public void setRelationshipWeight(
			@Name("startObjectId") String startObjectId,
			@Name("endObjectId") String endObjectId,
			@Name("label") String label,
			@Name("weight") Double weight,
			@Name(value = "token", defaultValue = "") String token) {
		SetRelationshipWeightService service = SetRelationshipWeightService.getInstance();
		service.setRelationshipWeight(tx, startObjectId, endObjectId, label, weight, token);
	}
}
