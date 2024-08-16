package deposit.api;

import deposit.service.CreateUserToolService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class CreateUserToolAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Create a User Tool.
	 *
	 * @param userToolName name of the User Tool.
	 * @param data JSON encoded string representing the definition of the User Tool.
	 * @param comment annotation to provide additional information about the User Tool, default is an empty string.
	 * @param token authentication token.
	 * @return the created User Tool node.
	 * @throws RuntimeException if User Tool with the same name already exists or there is an error during the creation.
	 */
	@Procedure(name = "deposit.create.userTool", mode = Mode.WRITE)
	@Description("CALL deposit.create.userTool(userToolName, data, comment, token) YIELD node AS userTool")
	public Stream<NodeResult> createUserTool(
			@Name("userToolName") String userToolName,
			@Name("data") String data,
			@Name(value = "comment", defaultValue = "") String comment,
			@Name(value = "token", defaultValue = "") String token) {
		CreateUserToolService service = CreateUserToolService.getInstance();
		Node node = service.createUserTool(tx, userToolName, data, comment, token);
		return Stream.of(new NodeResult(node));
	}
}
