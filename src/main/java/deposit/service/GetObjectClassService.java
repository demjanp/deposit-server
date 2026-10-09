package deposit.service;

import deposit.repository.GetObjectClassRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

public class GetObjectClassService extends BaseService {

	private static GetObjectClassService instance;
	private final GetObjectClassRepository getObjectClassRepository;

	private GetObjectClassService() {
		this.getObjectClassRepository = GetObjectClassRepository.getInstance();
	}

	public static synchronized GetObjectClassService getInstance() {
		if (instance == null) {
			instance = new GetObjectClassService();
		}
		return instance;
	}

	public Node getObjectClass(Transaction tx, String objectId, String token) {
		
		validateToken(token);
		validateId(objectId);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve the Class without an active Namespace.");
		}
		
		return getObjectClassRepository.getObjectClass(tx, namespaceName, objectId);
	}
}
