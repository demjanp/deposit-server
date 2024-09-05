package deposit.api;

import deposit.service.CreateRelationshipService;
import deposit.common.result.RelationshipResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Relationship;

import java.util.stream.Stream;

public class CreateRelationshipAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Create a Relationship.
	 *
	 * @param startObjectId the Id of the start Object.
	 * @param endObjectId the Id of the end Object.
	 * @param label the label of the Relationship.
	 * @param weight the weight of the Relationship, if null is passed it defaults to 0.
	 * @param token authentication token.
	 * @return the created Relationship.
	 * @throws RuntimeException if the Relationship already exists or if there is an error during the creation.
	 */
	@Procedure(name = "deposit.create.relationship", mode = Mode.WRITE)
	@Description("CALL deposit.create.relationship(startObjectId, endObjectId, label, weight, token) YIELD rel")
	public Stream<RelationshipResult> createRelationship(
			@Name("startObjectId") String startObjectId,
			@Name("endObjectId") String endObjectId,
			@Name("label") String label,
			@Name("weight") Double weight,
			@Name(value = "token", defaultValue = "") String token) {
		if (weight == null) {
			weight = 0.0;
		}
		CreateRelationshipService service = CreateRelationshipService.getInstance();
		Relationship rel = service.createRelationship(tx, startObjectId, endObjectId, label, weight, token);
		return Stream.of(new RelationshipResult(rel));
	}
}
