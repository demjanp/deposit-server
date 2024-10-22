package deposit.api;

import deposit.service.GetObjectsByPatternService;
import deposit.common.result.IdMapResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.Map;
import java.util.stream.Stream;

public class GetObjectsByPatternAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Try to retrieve Objects from an identical data structure in the active Namespace as the one specified by the Pattern and data.
	 *
	 * @param patternName name of the Pattern.
	 * @param data a Map of the Pattern Object names, their Descriptor names and values. {objectName: {descriptorName: value, ...}, ...}
	 * @param token authentication token.
	 * @return a Map of the Pattern Object names and the ids of the matched Objects in the current Namespace.
	 * @throws RuntimeException if there is an error retrieving the Objects.
	 */
	@Procedure(name = "deposit.get.objectsByPattern", mode = Mode.WRITE)
	@Description("CALL deposit.get.objectsByPattern(patternName, data, token) YIELD data")
	public Stream<IdMapResult> getObjectsByPattern(
			@Name("patternName") String patternName,
			@Name("data") Map<String, Object> data,
			@Name(value = "token", defaultValue = "") String token) {
		GetObjectsByPatternService service = GetObjectsByPatternService.getInstance();
		return service.getObjectsByPattern(tx, patternName, data, token).map(IdMapResult::new);
	}
}
