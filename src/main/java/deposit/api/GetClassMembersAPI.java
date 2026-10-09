package deposit.api;

import deposit.service.GetClassMembersService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.stream.Stream;

public class GetClassMembersAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get Members of a Class.
	 *
	 * @param className the name of the Class.
	 * @param token authentication token.
	 * @return the Object nodes.
	 * @throws RuntimeException if there was an error retrieving the node(s).
	 */
	@Procedure(name = "deposit.get.classMembers", mode = Mode.READ)
	@Description("CALL deposit.get.classMembers(className, token) YIELD node AS object")
	public Stream<NodeResult> getClassMembers(
			@Name("className") String className,
			@Name(value = "token", defaultValue = "") String token) {
		GetClassMembersService service = GetClassMembersService.getInstance();
		return service.getClassMembers(tx, className, token).map(NodeResult::new);
	}
}
