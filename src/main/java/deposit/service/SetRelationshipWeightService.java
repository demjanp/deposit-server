package deposit.service;

import deposit.repository.SetRelationshipWeightRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;

public class SetRelationshipWeightService extends BaseService {

	private static SetRelationshipWeightService instance;
	private final SetRelationshipWeightRepository setRelationshipWeightRepository;

	private SetRelationshipWeightService() {
		this.setRelationshipWeightRepository = SetRelationshipWeightRepository.getInstance();
	}

	public static synchronized SetRelationshipWeightService getInstance() {
		if (instance == null) {
			instance = new SetRelationshipWeightService();
		}
		return instance;
	}

	public void setRelationshipWeight(Transaction tx, String startObjectId, String endObjectId, String label, Double weight, String token) {
		
		if (startObjectId.equals(endObjectId)) {
			throwException("Cannot create a Relationship of an Object with itself.");
		}
		validateToken(token);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot update a Relationship without an active Namespace.");
		}
		
		setRelationshipWeightRepository.setRelationshipWeight(tx, timeStamp, username, namespaceName, versioningType, startObjectId, endObjectId, label, weight);
	}
}
