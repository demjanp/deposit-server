package deposit.api;

import deposit.service.GetPatternService;
import deposit.common.result.MapResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.stream.Stream;

public class GetPatternAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Retrieve a Pattern.
	 *
	 * @param patternName name of the Pattern.
	 * @param token authentication token.
	 * @return a Map with Pattern names as keys and Maps representing PatternData.Pattern objects as values.
	 * @throws RuntimeException if there is an error retrieving the Pattern.
	 */
	@Procedure(name = "deposit.get.pattern", mode = Mode.READ)
	@Description("CALL deposit.get.pattern(patternName, token) YIELD data")
	public Stream<MapResult> getPattern(
			@Name("patternName") String patternName,
			@Name(value = "token", defaultValue = "") String token) {
		GetPatternService service = GetPatternService.getInstance();
		MapResult data = service.getPattern(tx, patternName, token);
		return Stream.of(data);
	}
}
