package deposit.service;

import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;

public class DeleteClassRelationshipService extends BaseService {

	private static DeleteClassRelationshipService instance;
	private final EnsureClassRelationshipDeletionService ensureClassRelationshipDeletionService;

	private DeleteClassRelationshipService() {
		this.ensureClassRelationshipDeletionService = EnsureClassRelationshipDeletionService.getInstance();
	}

	public static synchronized DeleteClassRelationshipService getInstance() {
		if (instance == null) {
			instance = new DeleteClassRelationshipService();
		}
		return instance;
	}

	public void deleteClassRelationship(Transaction tx, String startClassName, String endClassName, String label, String token) {
		
		if (startClassName.equals(endClassName)) {
			throwException("Cannot delete a Class Relationship within the same Class.");
		}
		validateToken(token);
		validateName(startClassName);
		validateName(endClassName);
		validateName(label);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot delete a Class Relationship without an active Namespace.");
		}
		
		ensureClassRelationshipDeletionService.ensureClassRelationshipDeletion(tx, timeStamp, username, namespaceName, versioningType, startClassName, endClassName, label);
	}
}
