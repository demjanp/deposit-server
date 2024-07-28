package deposit.api;

import deposit.service.UtilsLoginService;
import deposit.common.result.StringResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.stream.Stream;

public class UtilsLoginAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Brief description.
	 *
	 * @param username the username.
	 * @param password the password (unhashed).
	 * @return the value.
	 * @throws RuntimeException if error condition is fulfilled.
	 */
	@Procedure(name = "deposit.utils.login", mode = Mode.READ)
	@Description("CALL deposit.utils.login(username, password) YIELD value AS token")
	public Stream<StringResult> utilsLogin(
			@Name(value = "username", defaultValue = "") String username,
			@Name(value = "password", defaultValue = "") String password) {
		UtilsLoginService service = UtilsLoginService.getInstance();
		String value = service.utilsLogin(tx, username, password);
		return Stream.of(new StringResult(value));
	}
}
