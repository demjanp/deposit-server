package deposit.api;

import deposit.service.GetVersionService;
import deposit.common.result.StringResult;

import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;

import java.util.stream.Stream;

public class GetVersionAPI extends BaseAPI {
	/**
	 * Get the version of the Deposit server.
	 *
	 * @return the version as a string.
	 * @throws RuntimeException if there was an error retrieving the version.
	 */
	@Procedure(name = "deposit.get.version", mode = Mode.READ)
	@Description("CALL deposit.get.version() YIELD value AS version")
	public Stream<StringResult> getVersion() {
		GetVersionService service = GetVersionService.getInstance();
		String version = service.getVersion();
		return Stream.of(new StringResult(version));
	}
}
