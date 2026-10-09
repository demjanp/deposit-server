package deposit.api;

import deposit.service.SetClassCommentService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class SetClassCommentAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Set comment of a Class.
	 *
	 * @param className the name of the Class.
	 * @param comment annotation to provide additional information about the Class.
	 * @param token authentication token.
	 * @throws RuntimeException if there is an error setting the comment.
	 */
	@Procedure(name = "deposit.set.classComment", mode = Mode.WRITE)
	@Description("CALL deposit.set.classComment(className, comment, token)")
	public void setClassComment(
			@Name("className") String className,
			@Name("comment") String comment,
			@Name(value = "token", defaultValue = "") String token) {
		SetClassCommentService service = SetClassCommentService.getInstance();
		service.setClassComment(tx, className, comment, token);
	}
}
