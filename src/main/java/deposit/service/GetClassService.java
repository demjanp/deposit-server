package deposit.service;

import deposit.repository.GetClassRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetClassService extends BaseService {

	private static GetClassService instance;
	private final GetClassRepository getClassRepository;

	private GetClassService() {
		this.getClassRepository = GetClassRepository.getInstance();
	}

	public static synchronized GetClassService getInstance() {
		if (instance == null) {
			instance = new GetClassService();
		}
		return instance;
	}

	public Stream<Node> getClass(Transaction tx, String className, String token) {
		validateToken(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve Class without an active Namespace.");
		}
		return getClassRepository.getClass(tx, namespaceName, className);
	}
}
