package deposit.service;

import deposit.service.data.DataMatchQueryBuilder;
import deposit.repository.GetObjectsByDataRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.Map;
import java.util.stream.Stream;

public class GetObjectsByDataService extends BaseService {

	private static GetObjectsByDataService instance;
	private final GetObjectsByDataRepository getObjectsByDataRepository;

	private GetObjectsByDataService() {
		this.getObjectsByDataRepository = GetObjectsByDataRepository.getInstance();
	}

	public static synchronized GetObjectsByDataService getInstance() {
		if (instance == null) {
			instance = new GetObjectsByDataService();
		}
		return instance;
	}

	public Stream<Node> getObjectsByData(Transaction tx, Map<String, Object> data, boolean matchEmpty, String token) {
		
		validateToken(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve Objects without an active Namespace.");
		}
		
		DataMatchQueryBuilder.DataMatchQueryBuilderResult result = DataMatchQueryBuilder.buildQuery(data, matchEmpty);
		return getObjectsByDataRepository.getObjectsByData(tx, namespaceName, result.getQueryStr(), result.getValues());
	}
}
