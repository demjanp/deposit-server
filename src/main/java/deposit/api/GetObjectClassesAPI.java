package deposit.api;

import deposit.service.GetObjectClassesService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.stream.Stream;

public class GetObjectClassesAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get Classes of an Object.
	 *
	 * @param objectId the Id of the Object.
	 * @param token authentication token.
	 * @return the created node.
	 * @throws RuntimeException if there was an error retrieving the node(s).
	 */
	@Procedure(name = "deposit.get.objectClasses", mode = Mode.WRITE)
	@Description("CALL deposit.get.objectClasses(objectId, token) YIELD node AS class")
	public Stream<NodeResult> getObjectClasses(
			@Name("objectId") String objectId,
			@Name(value = "token", defaultValue = "") String token) {
		GetObjectClassesService service = GetObjectClassesService.getInstance();
		return service.getObjectClasses(tx, objectId, token).map(NodeResult::new);
	}
}
