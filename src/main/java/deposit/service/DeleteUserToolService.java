package deposit.service;

import deposit.repository.DeleteUserToolRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;

public class DeleteUserToolService extends BaseService {

	private static DeleteUserToolService instance;
	private final DeleteUserToolRepository deleteUserToolRepository;

	private DeleteUserToolService() {
		this.deleteUserToolRepository = DeleteUserToolRepository.getInstance();
	}

	public static synchronized DeleteUserToolService getInstance() {
		if (instance == null) {
			instance = new DeleteUserToolService();
		}
		return instance;
	}

	public void deleteUserTool(Transaction tx, String userToolName, String token) {
		
		validateToken(token);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot delete a User Tool without an active Namespace.");
		}
		
		deleteUserToolRepository.deleteUserTool(tx, timeStamp, username, namespaceName, versioningType, userToolName);
	}
}
