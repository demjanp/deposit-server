package deposit.api;

import deposit.service.GetQueryService;
import deposit.common.result.QueryResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.stream.Stream;

public class GetQueryAPI extends BaseAPI {

	@Context
	public Transaction tx;

	/**
	 * Executes a Deposit query and returns the result as QueryResult.
	 *
	 * @param queryStr the Deposit query string
	 * @param token the authentication token
	 * @return a stream of QueryResult objects. Use result.get(key) to retrieve a returned node. Keys are identical to the identifiers used in the SELECT clause.
	 * @throws RuntimeException if there is an error executing the query
	 */
	@Procedure(name = "deposit.get.query", mode = Mode.READ)
	@Description("CALL deposit.get.query(queryStr, token) YIELD result")
	public Stream<QueryResult> getQuery(
			@Name("queryStr") String queryStr,
			@Name(value = "token", defaultValue = "") String token) {

		GetQueryService service = GetQueryService.getInstance();

		return service.getQuery(tx, queryStr, token);
	}
}
