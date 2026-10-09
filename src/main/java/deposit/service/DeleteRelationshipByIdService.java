package deposit.service;

import deposit.repository.DeleteRelationshipByIdRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;

public class DeleteRelationshipByIdService extends BaseService {

	private static DeleteRelationshipByIdService instance;
	private final DeleteRelationshipByIdRepository deleteRelationshipByIdRepository;

	private DeleteRelationshipByIdService() {
		this.deleteRelationshipByIdRepository = DeleteRelationshipByIdRepository.getInstance();
	}

	public static synchronized DeleteRelationshipByIdService getInstance() {
		if (instance == null) {
			instance = new DeleteRelationshipByIdService();
		}
		return instance;
	}

	public void deleteRelationshipById(Transaction tx, String relationshipId, String token) {
		
		validateToken(token);
		validateId(relationshipId);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot create a Relationship without an active Namespace.");
		}
		
		deleteRelationshipByIdRepository.deleteRelationshipById(tx, timeStamp, username, namespaceName, versioningType, relationshipId);
	}
}
