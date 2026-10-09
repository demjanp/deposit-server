package deposit.api;

import deposit.service.GetDescriptorByIdService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetDescriptorByIdAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get an existing Descriptor node.
	 *
	 * @param descriptorId the Id of the Descriptor.
	 * @param token authentication token.
	 * @return the Descriptor node or null if it doesn't exist.
	 * @throws RuntimeException if there was an error retrieving the node(s).
	 */
	@Procedure(name = "deposit.get.descriptorById", mode = Mode.READ)
	@Description("CALL deposit.get.descriptorById(descriptorId, token) YIELD node AS descriptor")
	public Stream<NodeResult> getDescriptorById(
			@Name("descriptorId") String descriptorId,
			@Name(value = "token", defaultValue = "") String token) {
		GetDescriptorByIdService service = GetDescriptorByIdService.getInstance();
		Node node = service.getDescriptorById(tx, descriptorId, token);
		return node != null ? Stream.of(new NodeResult(node)) : Stream.empty();
	}
}
