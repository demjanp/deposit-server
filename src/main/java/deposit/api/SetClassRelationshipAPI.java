package deposit.api;

import deposit.service.SetClassRelationshipService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class SetClassRelationshipAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Create or Update a Class Relationship.
	 *
	 * @param startClassName the name of the start Class.
	 * @param endClassName the name of the end Class.
	 * @param label the label of the Relationship.
	 * @param comment annotation to provide additional information about the Relationship, default is an empty string.
	 * @param token authentication token.
	 * @return the Relationship node.
	 * @throws RuntimeException if there is an error during the update.
	 */
	@Procedure(name = "deposit.set.classRelationship", mode = Mode.WRITE)
	@Description("CALL deposit.set.classRelationship(startClassName, endClassName, label, comment, token) YIELD node AS relationship")
	public Stream<NodeResult> setClassRelationship(
			@Name("startClassName") String startClassName,
			@Name("endClassName") String endClassName,
			@Name("label") String label,
			@Name(value = "comment", defaultValue = "") String comment,
			@Name(value = "token", defaultValue = "") String token) {
		SetClassRelationshipService service = SetClassRelationshipService.getInstance();
		Node node = service.setClassRelationship(tx, startClassName, endClassName, label, comment, token);
		return Stream.of(new NodeResult(node));
	}
}
