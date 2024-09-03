package deposit.service;

import deposit.repository.SetClassPositionRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

import java.util.List;
import java.time.ZonedDateTime;

public class SetClassPositionService extends BaseService {

	private static SetClassPositionService instance;
	private final SetClassPositionRepository setClassPositionRepository;

	private SetClassPositionService() {
		this.setClassPositionRepository = SetClassPositionRepository.getInstance();
	}

	public static synchronized SetClassPositionService getInstance() {
		if (instance == null) {
			instance = new SetClassPositionService();
		}
		return instance;
	}

	public void setClassPosition(Transaction tx, String className, List<Double> position, String token) {
		
		validateToken(token);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot set Class position without an active Namespace.");
		}
		
		setClassPositionRepository.setClassPosition(tx, timeStamp, username, namespaceName, versioningType, className, position);
	}
}
