package deposit.api;

import deposit.service.SetDescriptorService;
import deposit.common.result.NodeResult;

import org.neo4j.graphdb.Node;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.stream.Stream;

public class SetDescriptorAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Create or update a Descriptor.
	 *
	 * @param objectId the Id of the Object.
	 * @param descriptorName the name of the Descriptor to set.
	 * @param value can be of any Neo4j type or a DResource node.
	 * @param token authentication token.
	 * @return the Descriptor node.
	 * @throws RuntimeException if there is an error creating or updating the Descriptor.
	 */
	@Procedure(name = "deposit.set.descriptor", mode = Mode.WRITE)
	@Description("CALL deposit.set.descriptor(objectId, descriptorName, value, token) YIELD node AS descriptor")
	public Stream<NodeResult> setDescriptor(
			@Name("objectId") String objectId,
			@Name("descriptorName") String descriptorName,
			@Name("value") Object value,
			@Name(value = "token", defaultValue = "") String token) {
		SetDescriptorService service = SetDescriptorService.getInstance();
		Node node = service.setDescriptor(tx, objectId, descriptorName, value, token);
		return Stream.of(new NodeResult(node));
	}
}
