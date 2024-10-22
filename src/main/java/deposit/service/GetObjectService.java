package deposit.service;

import deposit.repository.GetObjectRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.List;
import java.util.stream.Stream;

public class GetObjectService extends BaseService {

	private static GetObjectService instance;
	private final GetObjectRepository getObjectRepository;

	private GetObjectService() {
		this.getObjectRepository = GetObjectRepository.getInstance();
	}

	public static synchronized GetObjectService getInstance() {
		if (instance == null) {
			instance = new GetObjectService();
		}
		return instance;
	}

	public Stream<Node> getObject(Transaction tx, String className, List<List<Object>> descriptors, String token) {
		
		validateToken(token);
		if (!className.isEmpty()) {
			validateName(className);
		}
		validateDescriptors(descriptors);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve Objects without an active Namespace.");
		}
		
		return getObjectRepository.getObject(tx, namespaceName, className, descriptors);
	}
}
