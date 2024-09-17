package deposit.service;

import deposit.repository.GetDataRepository;
import deposit.common.ServerContext;
import deposit.common.result.MapResult;

import org.neo4j.graphdb.Transaction;

import java.util.List;

public class GetDataService extends BaseService {

	private static GetDataService instance;
	private final GetDataRepository getDataRepository;

	private GetDataService() {
		this.getDataRepository = GetDataRepository.getInstance();
	}

	public static synchronized GetDataService getInstance() {
		if (instance == null) {
			instance = new GetDataService();
		}
		return instance;
	}

	public MapResult getData(Transaction tx, List<String> rootIds, String token) {
		
		validateToken(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve data without an active Namespace.");
		}
		
		return getDataRepository.getData(tx, namespaceName, rootIds);
	}
}
