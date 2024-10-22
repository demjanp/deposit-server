package deposit.service;

import deposit.repository.GetPatternRepository;
import deposit.common.ServerContext;
import deposit.common.result.MapResult;

import org.neo4j.graphdb.Transaction;



public class GetPatternService extends BaseService {

	private static GetPatternService instance;
	private final GetPatternRepository getPatternRepository;

	private GetPatternService() {
		this.getPatternRepository = GetPatternRepository.getInstance();
	}

	public static synchronized GetPatternService getInstance() {
		if (instance == null) {
			instance = new GetPatternService();
		}
		return instance;
	}

	public MapResult getPattern(Transaction tx, String patternName, String token) {
		
		validateToken(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve pattern without an active Namespace.");
		}
		
		return getPatternRepository.getPattern(tx, namespaceName, patternName);
	}
}
