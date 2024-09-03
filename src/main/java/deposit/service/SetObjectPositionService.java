package deposit.service;

import deposit.repository.SetObjectPositionRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

import java.util.List;
import java.time.ZonedDateTime;

public class SetObjectPositionService extends BaseService {

	private static SetObjectPositionService instance;
	private final SetObjectPositionRepository setObjectPositionRepository;

	private SetObjectPositionService() {
		this.setObjectPositionRepository = SetObjectPositionRepository.getInstance();
	}

	public static synchronized SetObjectPositionService getInstance() {
		if (instance == null) {
			instance = new SetObjectPositionService();
		}
		return instance;
	}

	public void setObjectPosition(Transaction tx, String objectId, List<Double> position, String token) {
		
		validateToken(token);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot set Object position without an active Namespace.");
		}
		
		setObjectPositionRepository.setObjectPosition(tx, timeStamp, username, namespaceName, versioningType, objectId, position);
	}
}
