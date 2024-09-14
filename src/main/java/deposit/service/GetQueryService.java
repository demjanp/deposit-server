package deposit.service;

import deposit.service.query.QueryTranslator;
import deposit.repository.GetQueryRepository;
import deposit.common.ServerContext;
import deposit.common.result.QueryResult;

import org.neo4j.graphdb.Transaction;

import java.util.stream.Stream;
import java.util.List;

public class GetQueryService extends BaseService {

	private static GetQueryService instance;
	private final GetQueryRepository getQueryRepository;

	private GetQueryService() {
		this.getQueryRepository = GetQueryRepository.getInstance();
	}

	public static synchronized GetQueryService getInstance() {
		if (instance == null) {
			instance = new GetQueryService();
		}
		return instance;
	}

	public Stream<QueryResult> getQuery(Transaction tx, String queryStr, String token) {

		validateToken(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot process query without an active Namespace.");
		}

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(queryStr);
		String cypherQueryStr = result.getCypherQuery();
		List<String> returnedNodes = result.getReturnedNodes();
		List<String> returnedKeys = result.getReturnedKeys();

		return getQueryRepository.getQuery(tx, namespaceName, cypherQueryStr, returnedNodes)
			.map(nodeArray -> {
				QueryResult queryResult = new QueryResult();
				for (int i = 0; i < returnedKeys.size(); i++) {
					queryResult.addNode(returnedKeys.get(i), nodeArray[i]);
				}
				return queryResult;
			});
	}
}
