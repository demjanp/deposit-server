package deposit.api;

import deposit.service.CreateDescriptorService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class CreateDescriptorAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Create a Descriptor.
	 *
	 * @param objectId the Id of the Object.
	 * @param descriptorName the name of the Descriptor to create.
	 * @param value can be of any Neo4j type or a DResource node.
	 * @param token authentication token.
	 * @return the created Descriptor node.
	 * @throws RuntimeException if a Descriptor with that name already exists for the Object or if there is an error during the creation.
	 */
	@Procedure(name = "deposit.create.descriptor", mode = Mode.WRITE)
	@Description("CALL deposit.create.descriptor(objectId, descriptorName, value, token) YIELD node AS descriptor")
	public Stream<NodeResult> createDescriptor(
			@Name("objectId") String objectId,
			@Name("descriptorName") String descriptorName,
			@Name("value") Object value,
			@Name(value = "token", defaultValue = "") String token) {
		CreateDescriptorService service = CreateDescriptorService.getInstance();
		Node node = service.createDescriptor(tx, objectId, descriptorName, value, token);
		return Stream.of(new NodeResult(node));
	}
}
