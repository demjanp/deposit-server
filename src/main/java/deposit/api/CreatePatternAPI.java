package deposit.api;

import deposit.service.CreatePatternService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.Map;
import java.util.stream.Stream;

public class CreatePatternAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Create a Pattern.
	 *
	 * @param data Map representing a PatternData.Pattern object.
	 * @param token authentication token.
	 * @return the created Pattern node.
	 * @throws RuntimeException if there is an error creating the Pattern.
	 */
	@Procedure(name = "deposit.create.pattern", mode = Mode.WRITE)
	@Description("CALL deposit.create.pattern(data, token) YIELD node AS pattern")
	public Stream<NodeResult> createPattern(
			@Name("data") Map<String, Object> data,
			@Name(value = "token", defaultValue = "") String token) {
		CreatePatternService service = CreatePatternService.getInstance();
		Node node = service.createPattern(tx, data, token);
		return Stream.of(new NodeResult(node));
	}
}
