package deposit.service;

import deposit.repository.GetNamespaceDataRepository;
import deposit.common.ServerContext;
import deposit.common.result.MapResult;

import org.neo4j.graphdb.Transaction;

public class GetNamespaceDataService extends BaseService {

	private static GetNamespaceDataService instance;
	private final GetNamespaceDataRepository getNamespaceDataRepository;

	private GetNamespaceDataService() {
		this.getNamespaceDataRepository = GetNamespaceDataRepository.getInstance();
	}

	public static synchronized GetNamespaceDataService getInstance() {
		if (instance == null) {
			instance = new GetNamespaceDataService();
		}
		return instance;
	}

	public MapResult getNamespaceData(Transaction tx, String token) {
		
		validateToken(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve data without an active Namespace.");
		}
		
		return getNamespaceDataRepository.getNamespaceData(tx, namespaceName);
	}
}
