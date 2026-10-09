package deposit.service;

import deposit.repository.GetObjectByIdRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

public class GetObjectByIdService extends BaseService {

	private static GetObjectByIdService instance;
	private final GetObjectByIdRepository getObjectByIdRepository;

	private GetObjectByIdService() {
		this.getObjectByIdRepository = GetObjectByIdRepository.getInstance();
	}

	public static synchronized GetObjectByIdService getInstance() {
		if (instance == null) {
			instance = new GetObjectByIdService();
		}
		return instance;
	}

	public Node getObjectById(Transaction tx, String objectId, String token) {
		
		validateToken(token);
		validateId(objectId);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve Object without an active Namespace.");
		}
		
		return getObjectByIdRepository.getObjectById(tx, namespaceName, objectId);
	}
}
