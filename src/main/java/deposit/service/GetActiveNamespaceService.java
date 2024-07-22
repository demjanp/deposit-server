package deposit.service;

import deposit.repository.GetActiveNamespaceRepository;
import deposit.common.SessionContext;

import deposit.repository.GetActiveNamespaceRepository;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

public class GetActiveNamespaceService extends BaseService {

	private static GetActiveNamespaceService instance;
	private final GetActiveNamespaceRepository getActiveNamespaceRepository;

	private GetActiveNamespaceService() {
		this.getActiveNamespaceRepository = GetActiveNamespaceRepository.getInstance();
	}

	public static synchronized GetActiveNamespaceService getInstance() {
		if (instance == null) {
			instance = new GetActiveNamespaceService();
		}
		return instance;
	}

	public Node getActiveNamespace(Transaction tx) {
		String name = SessionContext.getActiveNamespace();
		if (name == null) {
			return null;
		} else {
			return getActiveNamespaceRepository.getActiveNamespace(tx, name);
		}
	}
}
