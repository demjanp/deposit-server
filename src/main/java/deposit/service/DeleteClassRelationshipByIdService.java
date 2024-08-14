package deposit.service;

import deposit.repository.DeleteClassRelationshipByIdRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;

public class DeleteClassRelationshipByIdService extends BaseService {

	private static DeleteClassRelationshipByIdService instance;
	private final DeleteClassRelationshipByIdRepository deleteClassRelationshipByIdRepository;

	private DeleteClassRelationshipByIdService() {
		this.deleteClassRelationshipByIdRepository = DeleteClassRelationshipByIdRepository.getInstance();
	}

	public static synchronized DeleteClassRelationshipByIdService getInstance() {
		if (instance == null) {
			instance = new DeleteClassRelationshipByIdService();
		}
		return instance;
	}

	public void deleteClassRelationshipById(Transaction tx, String relationshipId, String token) {
		
		validateToken(token);
		validateId(relationshipId);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot create a Class Relationship without an active Namespace.");
		}
		
		deleteClassRelationshipByIdRepository.deleteClassRelationshipById(tx, timeStamp, username, namespaceName, versioningType, relationshipId);
	}
}
