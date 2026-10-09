package deposit.api;

import deposit.service.SetRelationshipService;
import deposit.common.result.RelationshipResult;

import org.neo4j.graphdb.Relationship;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.stream.Stream;

public class SetRelationshipAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Create or Update a Relationship.
	 *
	 * @param startObjectId the Id of the start Object.
	 * @param endObjectId the Id of the end Object.
	 * @param label the label of the Relationship.
	 * @param weight the weight of the Relationship, if null is passed it defaults to 0.
	 * @param token authentication token.
	 * @return the Relationship node.
	 * @throws RuntimeException if there is an error during the update.
	 */
	@Procedure(name = "deposit.set.relationship", mode = Mode.WRITE)
	@Description("CALL deposit.set.relationship(startObjectId, endObjectId, label, weight, token) YIELD rel")
	public Stream<RelationshipResult> setRelationship(
			@Name("startObjectId") String startObjectId,
			@Name("endObjectId") String endObjectId,
			@Name("label") String label,
			@Name("weight") Double weight,
			@Name(value = "token", defaultValue = "") String token) {
		if (weight == null) {
			weight = 0.0;
		}
		SetRelationshipService service = SetRelationshipService.getInstance();
		Relationship rel = service.setRelationship(tx, startObjectId, endObjectId, label, weight, token);
		return Stream.of(new RelationshipResult(rel));
	}
}
