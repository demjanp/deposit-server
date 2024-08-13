package deposit.api;

import deposit.service.CreateObjectService;
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

public class CreateObjectAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Create an Object.
	 *
	 * @param classes a list of Class names, needs to contain at least one item.
	 * @param descriptors Descriptors of the Object defined as a list of lists [[<name>, <value>], ...], where <name> is the name of the Descriptor, <value> is any Neo4j or Deposit type. Default is [].
	 * @param position position of the node in a hierarchical graph layout defined as [x, y], default is [].
	 * @param token authentication token.
	 * @return the created Object node.
	 * @throws RuntimeException if there is an error during the creation.
	 */
	@Procedure(name = "deposit.create.object", mode = Mode.WRITE)
	@Description("CALL deposit.create.object(classes, descriptors, position, token) YIELD node AS object")
	public Stream<NodeResult> createObject(
			@Name("classes") List<String> classes,
			@Name(value = "descriptors", defaultValue = "[]") List<List<Object>> descriptors,
			@Name(value = "position", defaultValue = "[]") List<Double> position,
			@Name(value = "token", defaultValue = "") String token) {
		if (position == null) {
			position = List.of();  // Default to an empty list if 'position' is null
		}
		
		if (descriptors == null) {
			descriptors = List.of();  // Default to an empty list if 'descriptors' is null
		}
		
		CreateObjectService service = CreateObjectService.getInstance();
		Node node = service.createObject(tx, classes, descriptors, position, token);
		return Stream.of(new NodeResult(node));
	}
}
