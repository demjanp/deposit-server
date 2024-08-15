package deposit.api;

import deposit.service.GetClassRelationshipService;
import deposit.common.result.RelationshipResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.stream.Stream;

public class GetClassRelationshipAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get existing Class Relationship nodes.
	 *
	 * @param startClassName the name of the start Class.
	 * @param endClassName the name of the end Class or empty for all related Classes.
	 * @param label the label of the Class Relationship or empty for all Relationships.
	 * @param token authentication token.
	 * @return the source, target, relationship node(s) or null if it doesn't exist.
	 * @throws RuntimeException if there was an error retrieving the node(s).
	 */
	@Procedure(name = "deposit.get.classRelationship", mode = Mode.WRITE)
	@Description("CALL deposit.get.classRelationship(startClassName, endClassName, label, token) YIELD source, target, relationship")
	public Stream<RelationshipResult> getClassRelationship(
			@Name("startClassName") String startClassName,
			@Name(value = "endClassName", defaultValue = "") String endClassName,
			@Name(value = "label", defaultValue = "") String label,
			@Name(value = "token", defaultValue = "") String token) {
		GetClassRelationshipService service = GetClassRelationshipService.getInstance();
		return service.getClassRelationship(tx, startClassName, endClassName, label, token)
						.map(nodes -> new RelationshipResult(nodes[0], nodes[1], nodes[2]));
	}
}
