package deposit.service;

import deposit.repository.DeleteNamespaceRepository;

import org.neo4j.graphdb.Transaction;

public class DeleteNamespaceService extends BaseService {

	private static DeleteNamespaceService instance;
	private final DeleteNamespaceRepository deleteNamespaceRepository;

	private DeleteNamespaceService() {
		this.deleteNamespaceRepository = DeleteNamespaceRepository.getInstance();
	}

	public static synchronized DeleteNamespaceService getInstance() {
		if (instance == null) {
			instance = new DeleteNamespaceService();
		}
		return instance;
	}

	public void deleteNamespace(Transaction tx, String namespaceName, String token) {
		
		validateToken(token);
		
		deleteNamespaceRepository.deleteNamespace(tx, namespaceName);
	}
}
