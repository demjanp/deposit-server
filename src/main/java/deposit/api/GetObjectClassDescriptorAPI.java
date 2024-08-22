package deposit.api;

import deposit.service.GetObjectClassDescriptorService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetObjectClassDescriptorAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get the ClassDescriptor of the Object's Class.
	 *
	 * @param objectId the Id of the Object.
	 * @param descriptorName name of the Descriptor to retrieve or empty for all Descriptors of the Object.
	 * @param token authentication token.
	 * @return the Class Descriptor node or null if it doesn't exist.
	 * @throws RuntimeException if there was an error retrieving the node.
	 */
	@Procedure(name = "deposit.get.objectClassDescriptor", mode = Mode.READ)
	@Description("CALL deposit.get.objectClassDescriptor(objectId, descriptorName, token) YIELD node AS descriptor")
	public Stream<NodeResult> getObjectClassDescriptor(
			@Name("objectId") String objectId,
			@Name("descriptorName") String descriptorName,
			@Name(value = "token", defaultValue = "") String token) {
		GetObjectClassDescriptorService service = GetObjectClassDescriptorService.getInstance();
		Node node = service.getObjectClassDescriptor(tx, objectId, descriptorName, token);
		return node != null ? Stream.of(new NodeResult(node)) : Stream.empty();
	}
}
