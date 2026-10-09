package deposit.api;

import deposit.service.GetClassDescriptorByIdService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetClassDescriptorByIdAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get existing Class Descriptor nodes.
	 *
	 * @param descriptorId the Id of the Class Descriptor.
	 * @param token authentication token.
	 * @return the Class Descriptor node or null if it doesn't exist.
	 * @throws RuntimeException if there was an error retrieving the node.
	 */
	@Procedure(name = "deposit.get.classDescriptorById", mode = Mode.READ)
	@Description("CALL deposit.get.classDescriptorById(descriptorId, token) YIELD node AS descriptor")
	public Stream<NodeResult> getClassDescriptorById(
			@Name("descriptorId") String descriptorId,
			@Name(value = "token", defaultValue = "") String token) {
		GetClassDescriptorByIdService service = GetClassDescriptorByIdService.getInstance();
		Node node = service.getClassDescriptorById(tx, descriptorId, token);
		return node != null ? Stream.of(new NodeResult(node)) : Stream.empty();
	}
}
