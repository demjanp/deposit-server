package deposit.api;

import deposit.service.GetObjectClassService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetObjectClassAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get Classes of an Object.
	 *
	 * @param objectId the Id of the Object.
	 * @param token authentication token.
	 * @return the Class nodes.
	 * @throws RuntimeException if there was an error retrieving the node(s).
	 */
	@Procedure(name = "deposit.get.objectClass", mode = Mode.READ)
	@Description("CALL deposit.get.objectClass(objectId, token) YIELD node AS class")
	public Stream<NodeResult> getObjectClass(
			@Name("objectId") String objectId,
			@Name(value = "token", defaultValue = "") String token) {
		GetObjectClassService service = GetObjectClassService.getInstance();
		Node node = service.getObjectClass(tx, objectId, token);
		return Stream.of(new NodeResult(node));
	}
}
