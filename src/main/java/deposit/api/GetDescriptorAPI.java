package deposit.api;

import deposit.service.GetDescriptorService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.stream.Stream;

public class GetDescriptorAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get existing Descriptor nodes.
	 *
	 * @param objectId the Id of the Object.
	 * @param descriptorName name of the Descriptor to retrieve or empty for all Descriptors of the Object.
	 * @param token authentication token.
	 * @return the Descriptor node(s) or null if it doesn't exist.
	 * @throws RuntimeException if there was an error retrieving the node(s).
	 */
	@Procedure(name = "deposit.get.descriptor", mode = Mode.READ)
	@Description("CALL deposit.get.descriptor(objectId, descriptorName, token) YIELD node AS descriptor")
	public Stream<NodeResult> getDescriptor(
			@Name("objectId") String objectId,
			@Name(value = "descriptorName", defaultValue = "") String descriptorName,
			@Name(value = "token", defaultValue = "") String token) {
		GetDescriptorService service = GetDescriptorService.getInstance();
		return service.getDescriptor(tx, objectId, descriptorName, token).map(NodeResult::new);
	}
}
