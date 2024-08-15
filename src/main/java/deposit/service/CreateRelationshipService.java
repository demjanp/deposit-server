package deposit.service;

import deposit.repository.CreateRelationshipRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;

public class CreateRelationshipService extends BaseService {

	private static CreateRelationshipService instance;
	private final CreateRelationshipRepository createRelationshipRepository;

	private CreateRelationshipService() {
		this.createRelationshipRepository = CreateRelationshipRepository.getInstance();
	}

	public static synchronized CreateRelationshipService getInstance() {
		if (instance == null) {
			instance = new CreateRelationshipService();
		}
		return instance;
	}

	public Node createRelationship(Transaction tx, String startObjectId, String endObjectId, String label, Double weight, String token) {
		
		if (startObjectId == endObjectId) {
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
		
		// TODO : If Objects don't share a class, ensure Class Relationships between all Classes of the Objects (implement in repository layer?)
		
		return createRelationshipRepository.createRelationship(tx, timeStamp, username, namespaceName, versioningType, startObjectId, endObjectId, label, weight);
	}
}
