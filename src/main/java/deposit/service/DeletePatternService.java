package deposit.service;

import deposit.repository.GetPatternRepository;
import deposit.repository.DeletePatternRepository;
import deposit.common.result.MapResult;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;
import java.util.Map;

public class DeletePatternService extends BaseService {

	private static DeletePatternService instance;
	private final GetPatternRepository getPatternRepository;
	private final DeletePatternRepository deletePatternRepository;

	private DeletePatternService() {
		this.getPatternRepository = GetPatternRepository.getInstance();
		this.deletePatternRepository = DeletePatternRepository.getInstance();
	}

	public static synchronized DeletePatternService getInstance() {
		if (instance == null) {
			instance = new DeletePatternService();
		}
		return instance;
	}

	public void deletePattern(Transaction tx, String patternName, String token) {
		
		validateToken(token);
		validateName(patternName);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot delete Pattern without an active Namespace.");
		}
		
		MapResult result = getPatternRepository.getPattern(tx, namespaceName, patternName);
		if (!result.data.containsKey(patternName)) {
			throwException("Error deleting the Pattern " + patternName + ". It does not exist.");
		}
		deletePatternRepository.deletePattern(tx, timeStamp, username, namespaceName, versioningType, patternName);
	}
}
