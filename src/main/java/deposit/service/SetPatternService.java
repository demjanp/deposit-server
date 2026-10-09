package deposit.service;

import deposit.repository.SetPatternRepository;
import deposit.common.ServerContext;
import deposit.common.PatternData;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;
import java.util.Map;

public class SetPatternService extends BaseService {

	private static SetPatternService instance;
	private final SetPatternRepository setPatternRepository;

	private SetPatternService() {
		this.setPatternRepository = SetPatternRepository.getInstance();
	}

	public static synchronized SetPatternService getInstance() {
		if (instance == null) {
			instance = new SetPatternService();
		}
		return instance;
	}

	public void setPattern(Transaction tx, Map<String, Object> data, String token) {
		
		validateToken(token);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		
		PatternData.Pattern pattern = PatternData.parseData(data);
		validatePattern(pattern);
		
		setPatternRepository.setPattern(tx, timeStamp, username, namespaceName, versioningType, pattern);
	}
}
