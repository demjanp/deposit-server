package deposit.api;

import deposit.service.DeletePatternService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class DeletePatternAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Delete a Pattern.
	 *
	 * @param patternName name of the Pattern.
	 * @param token authentication token.
	 * @return a Map with Pattern names as keys and Maps representing PatternData.Pattern objects as values.
	 * @throws RuntimeException if there is an error deleting the Pattern.
	 */
	@Procedure(name = "deposit.delete.pattern", mode = Mode.WRITE)
	@Description("CALL deposit.delete.pattern(patternName, token)")
	public void deletePattern(
			@Name("patternName") String patternName,
			@Name(value = "token", defaultValue = "") String token) {
		DeletePatternService service = DeletePatternService.getInstance();
		service.deletePattern(tx, patternName, token);
	}
}
