package deposit.api;

import deposit.service.CreateClassService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.List;
import java.util.stream.Stream;

public class CreateClassAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Create a Class.
	 *
	 * @param className the name of the Class to create.
	 * @param order an order of the Class when represented in a list, default is -1.
	 * @param position position of the node in a hierarchical graph layout defined as [x, y], default is [].
	 * @param comment annotation to provide additional information about the Class, default is an empty string.
	 * @param descriptors Descriptors of the Class defined as a list of lists [[<name>, <type>] or [<name>, <type>, <comment>], ...], where <name> is the name of the Descriptor, <type> is any Neo4j or Deposit type and comment is an annotation. Default is [].
	 * @param token authentication token.
	 * @return the created Class node.
	 * @throws RuntimeException if a Class with that name already exists or if there is an error during the creation.
	 */
	@Procedure(name = "deposit.create.class", mode = Mode.WRITE)
	@Description("CALL deposit.create.class(className, order, position, comment, descriptors, token) YIELD node AS class")
	public Stream<NodeResult> createClass(
			@Name("className") String className,
			@Name(value = "order", defaultValue = "-1") Long order,
			@Name(value = "position", defaultValue = "[]") List<Double> position,
			@Name(value = "comment", defaultValue = "") String comment,
			@Name(value = "descriptors", defaultValue = "[]") List<List<String>> descriptors,
			@Name(value = "token", defaultValue = "") String token) {
		
		if (position == null) {
			position = List.of();  // Default to an empty list if 'position' is null
		}
		
		if (descriptors == null) {
			descriptors = List.of();  // Default to an empty list if 'descriptors' is null
		}
		
		CreateClassService service = CreateClassService.getInstance();
		Node node = service.createClass(tx, className, order, position, comment, descriptors, token);
		return Stream.of(new NodeResult(node));
	}
}
