package deposit.api;

import deposit.service.SetClassRelationshipCommentService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class SetClassRelationshipCommentAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Set comment of a Class Relationship.
	 *
	 * @param startClassName the name of the start Class.
	 * @param endClassName the name of the end Class.
	 * @param label the label of the Relationship.
	 * @param comment annotation to provide additional information about the Relationship.
	 * @param token authentication token.
	 * @throws RuntimeException if there is an error setting the comment.
	 */
	@Procedure(name = "deposit.set.classRelationshipComment", mode = Mode.WRITE)
	@Description("CALL deposit.set.classRelationshipComment(startClassName, endClassName, label, comment, token)")
	public void setClassRelationshipComment(
			@Name("startClassName") String startClassName,
			@Name("endClassName") String endClassName,
			@Name("label") String label,
			@Name("comment") String comment,
			@Name(value = "token", defaultValue = "") String token) {
		SetClassRelationshipCommentService service = SetClassRelationshipCommentService.getInstance();
		service.setClassRelationshipComment(tx, startClassName, endClassName, label, comment, token);
	}
}
