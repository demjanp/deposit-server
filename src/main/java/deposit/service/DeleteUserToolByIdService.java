package deposit.service;

import deposit.repository.GetUserToolByIdRepository;
import deposit.repository.DeleteUserToolRepository;

import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;

public class DeleteUserToolByIdService extends BaseService {

	private static DeleteUserToolByIdService instance;
	private final GetUserToolByIdRepository getUserToolByIdRepository;
	private final DeleteUserToolRepository deleteUserToolRepository;

	private DeleteUserToolByIdService() {
		this.getUserToolByIdRepository = GetUserToolByIdRepository.getInstance();
		this.deleteUserToolRepository = DeleteUserToolRepository.getInstance();
	}

	public static synchronized DeleteUserToolByIdService getInstance() {
		if (instance == null) {
			instance = new DeleteUserToolByIdService();
		}
		return instance;
	}

	public void deleteUserToolById(Transaction tx, String userToolId, String token) {
		
		validateToken(token);
		validateId(userToolId);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot delete a User Tool without an active Namespace.");
		}
		
		// Get the User Tool name
		Node userToolNode = getUserToolByIdRepository.getUserToolById(tx, namespaceName, userToolId);
		if (userToolNode == null) {
			throwException("Error deleting the User Tool " + userToolId + ". It does not exist.");
		}
		String userToolName = (String) userToolNode.getProperty("name");
		
		deleteUserToolRepository.deleteUserTool(tx, timeStamp, username, namespaceName, versioningType, userToolName);
	}
}
