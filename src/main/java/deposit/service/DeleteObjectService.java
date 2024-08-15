package deposit.service;

import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;

public class DeleteObjectService extends BaseService {

	private static DeleteObjectService instance;
	private final EnsureObjectDeletionService ensureObjectDeletionService;

	private DeleteObjectService() {
		this.ensureObjectDeletionService = EnsureObjectDeletionService.getInstance();
	}

	public static synchronized DeleteObjectService getInstance() {
		if (instance == null) {
			instance = new DeleteObjectService();
		}
		return instance;
	}

	public void deleteObject(Transaction tx, String objectId, String token) {

		validateToken(token);
		validateId(objectId);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot delete an Object without an active Namespace.");
		}
		
		ensureObjectDeletionService.ensureObjectDeletion(tx, timeStamp, username, namespaceName, versioningType, objectId);
	}
}
