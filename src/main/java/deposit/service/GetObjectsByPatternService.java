package deposit.service;

import deposit.repository.GetPatternRepository;
import deposit.repository.GetObjectsByPatternRepository;
import deposit.common.ServerContext;
import deposit.common.PatternData;
import deposit.common.result.MapResult;

import org.neo4j.graphdb.Transaction;

import java.util.Map;
import java.util.stream.Stream;

public class GetObjectsByPatternService extends BaseService {

	private static GetObjectsByPatternService instance;
	private final GetPatternRepository getPatternRepository;
	private final GetObjectsByPatternRepository getObjectsByPatternRepository;

	private GetObjectsByPatternService() {
		this.getPatternRepository = GetPatternRepository.getInstance();
		this.getObjectsByPatternRepository = GetObjectsByPatternRepository.getInstance();
	}

	public static synchronized GetObjectsByPatternService getInstance() {
		if (instance == null) {
			instance = new GetObjectsByPatternService();
		}
		return instance;
	}

	public Stream<Map<String, String>> getObjectsByPattern(Transaction tx, String patternName, Map<String, Object> data, String token) {
		
		validateToken(token);
		validateName(patternName);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve Objects without an active Namespace.");
		}
		
		MapResult result = getPatternRepository.getPattern(tx, namespaceName, patternName);

		if (!result.data.containsKey(patternName)) {
			throwException("Pattern " + patternName + " not found.");
		}

		Map<String, Object> patternData = (Map<String, Object>) result.data.get(patternName);

		PatternData.Pattern pattern = PatternData.parseData(patternData);
		validatePattern(pattern);
		
		return getObjectsByPatternRepository.getObjectsByPattern(tx, namespaceName, pattern, data);
	}
}
