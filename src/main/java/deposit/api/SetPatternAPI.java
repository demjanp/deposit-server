package deposit.api;

import deposit.service.SetPatternService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.Map;

public class SetPatternAPI extends BaseAPI {

	@Context
	public Transaction tx;
	
	/**
	 * Update or create a Pattern.
	 *
	 * @param data Map representing a PatternData.Pattern object.
	 * @param token authentication token.
	 * @throws RuntimeException if there is an error updating the Pattern.
	 */
	@Procedure(name = "deposit.set.pattern", mode = Mode.WRITE)
	@Description("CALL deposit.set.pattern(data, token)")
	public void setPattern(
			@Name("data") Map<String, Object> data,
			@Name(value = "token", defaultValue = "") String token) {
		SetPatternService service = SetPatternService.getInstance();
		service.setPattern(tx, data, token);
	}
}
