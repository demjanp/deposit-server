package deposit.service;

import deposit.repository.GetObjectPositionRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

public class GetObjectPositionService extends BaseService {

	private static GetObjectPositionService instance;
	private final GetObjectPositionRepository getObjectPositionRepository;

	private GetObjectPositionService() {
		this.getObjectPositionRepository = GetObjectPositionRepository.getInstance();
	}

	public static synchronized GetObjectPositionService getInstance() {
		if (instance == null) {
			instance = new GetObjectPositionService();
		}
		return instance;
	}

	public double[] getObjectPosition(Transaction tx, String objectId, String token) {
		
		validateToken(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve Object position without an active Namespace.");
		}
		
		return getObjectPositionRepository.getObjectPosition(tx, namespaceName, objectId);
	}
}
