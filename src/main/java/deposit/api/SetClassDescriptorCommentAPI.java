package deposit.api;

import deposit.service.SetClassDescriptorCommentService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class SetClassDescriptorCommentAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Set comment of a Class Descriptor.
	 *
	 * @param className the name of the Class.
	 * @param descriptorName the name of the Class Descriptor.
	 * @param comment annotation to provide additional information about the Class Descriptor.
	 * @param token authentication token.
	 * @throws RuntimeException if there is an error setting the comment.
	 */
	@Procedure(name = "deposit.set.classDescriptorComment", mode = Mode.WRITE)
	@Description("CALL deposit.set.classDescriptorComment(className, descriptorName, comment, token)")
	public void setClassDescriptorComment(
			@Name("className") String className,
			@Name("descriptorName") String descriptorName,
			@Name("comment") String comment,
			@Name(value = "token", defaultValue = "") String token) {
		SetClassDescriptorCommentService service = SetClassDescriptorCommentService.getInstance();
		service.setClassDescriptorComment(tx, className, descriptorName, comment, token);
	}
}
