package deposit.api;

import deposit.service.GetObjectClassDescriptorByIdService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetObjectClassDescriptorByIdAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get the corresponding ClassDescriptor to the Descriptor of an Object.
	 *
	 * @param descriptorId the Id of the Object's Descriptor.
	 * @param token authentication token.
	 * @return the Class Descriptor node or null if it doesn't exist.
	 * @throws RuntimeException if there was an error retrieving the node.
	 */
	@Procedure(name = "deposit.get.objectClassDescriptorById", mode = Mode.READ)
	@Description("CALL deposit.get.objectClassDescriptorById(descriptorId, token) YIELD node AS descriptor")
	public Stream<NodeResult> getObjectClassDescriptorById(
			@Name("descriptorId") String descriptorId,
			@Name(value = "token", defaultValue = "") String token) {
		GetObjectClassDescriptorByIdService service = GetObjectClassDescriptorByIdService.getInstance();
		Node node = service.getObjectClassDescriptorById(tx, descriptorId, token);
		return node != null ? Stream.of(new NodeResult(node)) : Stream.empty();
	}
}
