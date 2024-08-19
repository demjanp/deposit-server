package deposit.api;

import deposit.service.GetObjectByIdService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetObjectByIdAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get an existing Object node.
	 *
	 * @param objectId Id of the Object to retrieve.
	 * @param token authentication token.
	 * @return the Object node or null if it doesn't exist.
	 * @throws RuntimeException if there was an error retrieving the node.
	 */
	@Procedure(name = "deposit.get.objectById", mode = Mode.READ)
	@Description("CALL deposit.get.objectById(objectId, token) YIELD node AS object")
	public Stream<NodeResult> getObjectById(
			@Name("objectId") String objectId,
			@Name(value = "token", defaultValue = "") String token) {
		GetObjectByIdService service = GetObjectByIdService.getInstance();
		Node node = service.getObjectById(tx, objectId, token);
		return node != null ? Stream.of(new NodeResult(node)) : Stream.empty();
	}
}
