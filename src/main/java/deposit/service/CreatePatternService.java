package deposit.service;

import deposit.repository.CreatePatternRepository;
import deposit.common.PatternData;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;
import java.util.Map;

public class CreatePatternService extends BaseService {

	private static CreatePatternService instance;
	private final CreatePatternRepository createPatternRepository;

	private CreatePatternService() {
		this.createPatternRepository = CreatePatternRepository.getInstance();
	}

	public static synchronized CreatePatternService getInstance() {
		if (instance == null) {
			instance = new CreatePatternService();
		}
		return instance;
	}

	public Node createPattern(Transaction tx, Map<String, Object> data, String token) {
		
		validateToken(token);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		
		PatternData.Pattern pattern = PatternData.parseData(data);
		validatePattern(pattern);
		
		return createPatternRepository.createPattern(tx, timeStamp, username, namespaceName, versioningType, pattern);
	}
}
