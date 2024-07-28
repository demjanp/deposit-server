package deposit.service;

import deposit.repository.GetNamespaceRepository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetNamespaceService extends BaseService {

	private static GetNamespaceService instance;
	private final GetNamespaceRepository getNamespaceRepository;

	private GetNamespaceService() {
		this.getNamespaceRepository = GetNamespaceRepository.getInstance();
	}

	public static synchronized GetNamespaceService getInstance() {
		if (instance == null) {
			instance = new GetNamespaceService();
		}
		return instance;
	}

	public Stream<Node> getNamespace(Transaction tx, String namespaceName, String token) {
		
		validateToken(token);
		
		return getNamespaceRepository.getNamespace(tx, namespaceName);
	}
}
