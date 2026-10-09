package deposit.api;

import deposit.service.GetClassRelationshipByIdService;
import deposit.common.result.RelationshipResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Relationship;

import java.util.stream.Stream;

public class GetClassRelationshipByIdAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get existing Class Relationship.
	 *
	 * @param relationshipId the Id of the Class Relationship.
	 * @param token authentication token.
	 * @return the relationship or null if it doesn't exist.
	 * @throws RuntimeException if there was an error retrieving the relationship.
	 */
	@Procedure(name = "deposit.get.classRelationshipById", mode = Mode.READ)
	@Description("CALL deposit.get.classRelationshipById(relationshipId, token) YIELD rel")
	public Stream<RelationshipResult> getClassRelationshipById(
			@Name("relationshipId") String relationshipId,
			@Name(value = "token", defaultValue = "") String token) {
		GetClassRelationshipByIdService service = GetClassRelationshipByIdService.getInstance();
		Relationship rel = service.getClassRelationshipById(tx, relationshipId, token);
		return rel != null ? Stream.of(new RelationshipResult(rel)) : Stream.empty();
	}
}
