package deposit.service;

import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;

public class DeleteClassService extends BaseService {

	private static DeleteClassService instance;
	private final EnsureClassDeletionService ensureClassDeletionService;

	private DeleteClassService() {
		this.ensureClassDeletionService = EnsureClassDeletionService.getInstance();
	}

	public static synchronized DeleteClassService getInstance() {
		if (instance == null) {
			instance = new DeleteClassService();
		}
		return instance;
	}

	public void deleteClass(Transaction tx, String className, String token) {
		
		validateToken(token);
		validateName(className);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot create a Class without an active Namespace.");
		}
		
		ensureClassDeletionService.ensureClassDeletion(tx, timeStamp, username, namespaceName, versioningType, className);
	}
}
