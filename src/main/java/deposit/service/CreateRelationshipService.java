package deposit.service;

import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;
import java.util.stream.Stream;

public class CreateRelationshipService extends BaseService {

	private static CreateRelationshipService instance;
	private final EnsureRelationshipService ensureRelationshipService;

	private CreateRelationshipService() {
		this.ensureRelationshipService = EnsureRelationshipService.getInstance();
	}

	public static synchronized CreateRelationshipService getInstance() {
		if (instance == null) {
			instance = new CreateRelationshipService();
		}
		return instance;
	}

	public Node createRelationship(Transaction tx, String startObjectId, String endObjectId, String label, Double weight, String token) {
		
		if (startObjectId.equals(endObjectId)) {
			throwException("Cannot create a Relationship of an Object with itself.");
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
			throwException("Cannot create a Relationship without an active Namespace.");
		}
		
		return ensureRelationshipService.ensureRelationship(tx, timeStamp, username, namespaceName, versioningType, startObjectId, endObjectId, label, weight);
	}
}
