package deposit.api;

import deposit.service.GetClassDescriptorService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.stream.Stream;

public class GetClassDescriptorAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Get existing Class Descriptor nodes.
	 *
	 * @param className the name of the Class.
	 * @param descriptorName name of the Class Descriptor to retrieve or empty for all Descriptors of the Class.
	 * @param token authentication token.
	 * @return the Descriptor node(s) or null if it doesn't exist.
	 * @throws RuntimeException if there was an error retrieving the node(s).
	 */
	@Procedure(name = "deposit.get.classDescriptor", mode = Mode.WRITE)
	@Description("CALL deposit.get.classDescriptor(className, descriptorName, token) YIELD node AS descriptor")
	public Stream<NodeResult> getClassDescriptor(
			@Name("className") String className,
			@Name(value = "descriptorName", defaultValue = "") String descriptorName,
			@Name(value = "token", defaultValue = "") String token) {
		GetClassDescriptorService service = GetClassDescriptorService.getInstance();
		return service.getClassDescriptor(tx, className, descriptorName, token).map(NodeResult::new);
	}
}
