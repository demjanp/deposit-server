package deposit.api;

import deposit.service.GetClassByIdService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetClassByIdAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get an existing Class node.
	 *
	 * @param classId Id of the Class to retrieve.
	 * @param token authentication token.
	 * @return the Class node or null if it doesn't exist.
	 * @throws RuntimeException if there was an error retrieving the node.
	 */
	@Procedure(name = "deposit.get.classById", mode = Mode.READ)
	@Description("CALL deposit.get.classById(classId, token) YIELD node AS class")
	public Stream<NodeResult> getClassById(
			@Name("classId") String classId,
			@Name(value = "token", defaultValue = "") String token) {
		GetClassByIdService service = GetClassByIdService.getInstance();
		Node node = service.getClassById(tx, classId, token);
		return node != null ? Stream.of(new NodeResult(node)) : Stream.empty();
	}
}
