package deposit.service;

import deposit.repository.GetRelationshipRepository;
import deposit.repository.SetRelationshipWeightRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Relationship;

import java.time.ZonedDateTime;
import java.util.Optional;

public class SetRelationshipService extends BaseService {

	private static SetRelationshipService instance;
	private final GetRelationshipRepository getRelationshipRepository;
	private final SetRelationshipWeightRepository setRelationshipWeightRepository;
	private final EnsureRelationshipService ensureRelationshipService;

	private SetRelationshipService() {
		this.getRelationshipRepository = GetRelationshipRepository.getInstance();
		this.setRelationshipWeightRepository = SetRelationshipWeightRepository.getInstance();
		this.ensureRelationshipService = EnsureRelationshipService.getInstance();
	}

	public static synchronized SetRelationshipService getInstance() {
		if (instance == null) {
			instance = new SetRelationshipService();
		}
		return instance;
	}

	public Relationship setRelationship(Transaction tx, String startObjectId, String endObjectId, String label, Double weight, String token) {
		
		if (startObjectId.equals(endObjectId)) {
			throwException("Cannot set a Relationship of an Object with itself.");
		}
		validateToken(token);
		validateId(startObjectId);
		validateId(endObjectId);
		validateName(label);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot set a Relationship without an active Namespace.");
		}
		
		Optional<Relationship> result = getRelationshipRepository.getRelationship(tx, namespaceName, startObjectId, endObjectId, label).findAny();
		if (result.isEmpty()) {
			return ensureRelationshipService.ensureRelationship(tx, timeStamp, username, namespaceName, versioningType, startObjectId, endObjectId, label, weight);
		} else {
			// If the Relationship exists, just update the weight and return the Relationship node
			setRelationshipWeightRepository.setRelationshipWeight(tx, timeStamp, username, namespaceName, versioningType, startObjectId, endObjectId, label, weight);
			return result.orElseThrow(() -> new IllegalStateException("Incorrect Relationship result"));
		}
	}
}
