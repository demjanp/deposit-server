package deposit.api;

import deposit.service.CreateClassDescriptorService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class CreateClassDescriptorAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Create a Class Descriptor.
	 *
	 * @param className
	 * @param descriptorName
	 * @param type
	 * @param order
	 * @param comment
	 * @param token authentication token.
	 * @return the created Class Descriptor node.
	 * @throws RuntimeException if a Descriptor with that name already exists for the Class or if there is an error during the creation.
	 */
	@Procedure(name = "deposit.create.classDescriptor", mode = Mode.WRITE)
	@Description("CALL deposit.create.classDescriptor(className, descriptorName, type, order, comment, token) YIELD node AS descriptor")
	public Stream<NodeResult> createClassDescriptor(
			@Name("className") String className,
			@Name("descriptorName") String descriptorName,
			@Name("type") String type,
			@Name(value = "order", defaultValue = "-1") Long order,
			@Name(value = "comment", defaultValue = "") String comment,
			@Name(value = "token", defaultValue = "") String token) {
		CreateClassDescriptorService service = CreateClassDescriptorService.getInstance();
		Node node = service.createClassDescriptor(tx, className, descriptorName, type, order, comment, token);
		return Stream.of(new NodeResult(node));
	}
}
