package deposit.api;

import deposit.service.GetRelationshipByIdService;
import deposit.common.result.RelationshipResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetRelationshipByIdAPI extends BaseAPI {

	@Context
	public Transaction tx;

	/**
	 * Get existing Relationship node.
	 *
	 * @param relationshipId the Id of the Relationship.
	 * @param token authentication token.
	 * @return the source, target, relationship nodes or null if it doesn't exist.
	 * @throws RuntimeException if there was an error retrieving the nodes.
	 */
	@Procedure(name = "deposit.get.relationshipById", mode = Mode.READ)
	@Description("CALL deposit.get.relationshipById(relationshipId, token) YIELD source, target, relationship")
	public Stream<RelationshipResult> getRelationshipById(
			@Name("relationshipId") String relationshipId,
			@Name(value = "token", defaultValue = "") String token) {
		GetRelationshipByIdService service = GetRelationshipByIdService.getInstance();
		Node[] nodes = service.getRelationshipById(tx, relationshipId, token);
		return nodes != null ? Stream.of(new RelationshipResult(nodes[0], nodes[1], nodes[2])) : Stream.empty();
	}
}
