package deposit.api;

import deposit.service.CreateUserService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class CreateUserAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Create a User node.
	 *
	 * @param username new username to create.
	 * @param password password (unhashed).
	 * @param token authentication token.
	 * @return the created User node.
	 * @throws RuntimeException if User node couldn't be created.
	 */
	@Procedure(name = "deposit.create.user", mode = Mode.WRITE)
	@Description("CALL deposit.create.user(username, password, token) YIELD node AS user")
	public Stream<NodeResult> createUser(
			@Name("username") String username,
			@Name("password") String password,
			@Name(value = "token", defaultValue = "") String token) {
		CreateUserService service = CreateUserService.getInstance();
		Node node = service.createUser(tx, username, password, token);
		return Stream.of(new NodeResult(node));
	}
}
