package deposit.service;

import deposit.repository.GetClassPositionRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

public class GetClassPositionService extends BaseService {
	
	private static GetClassPositionService instance;
	private final GetClassPositionRepository getClassPositionRepository;
	
	private GetClassPositionService() {
		this.getClassPositionRepository = GetClassPositionRepository.getInstance();
	}
	
	public static synchronized GetClassPositionService getInstance() {
		if (instance == null) {
			instance = new GetClassPositionService();
		}
		return instance;
	}
	
	public double[] getClassPosition(Transaction tx, String className, String token) {
		
		validateToken(token);
		validateName(className);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve Class position without an active Namespace.");
		}
		
		return getClassPositionRepository.getClassPosition(tx, namespaceName, className);
	}
}
