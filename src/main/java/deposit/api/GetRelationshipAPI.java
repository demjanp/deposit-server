package deposit.api;

import deposit.service.GetRelationshipService;
import deposit.common.result.RelationshipResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.stream.Stream;

public class GetRelationshipAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get existing Relationship.
	 *
	 * @param startObjectId the Id of the start Object.
	 * @param endObjectId the Id of the end Object or empty for all related Objects.
	 * @param label the label of the Relationship or empty for all Relationships.
	 * @param token authentication token.
	 * @return the relationship(s) or null if it doesn't exist.
	 * @throws RuntimeException if there was an error retrieving the relationship(s).
	 */
	@Procedure(name = "deposit.get.relationship", mode = Mode.READ)
	@Description("CALL deposit.get.relationship(startObjectId, endObjectId, label, token) YIELD rel")
	public Stream<RelationshipResult> getRelationship(
			@Name("startObjectId") String startObjectId,
			@Name(value = "endObjectId", defaultValue = "") String endObjectId,
			@Name(value = "label", defaultValue = "") String label,
			@Name(value = "token", defaultValue = "") String token) {
		GetRelationshipService service = GetRelationshipService.getInstance();
		return service.getRelationship(tx, startObjectId, endObjectId, label, token)
						.map(rel -> new RelationshipResult(rel));
	}
}
