package deposit.service;

import deposit.repository.GetClassRepository;
import deposit.repository.GetUserToolRepository;
import deposit.repository.DeleteUserToolRepository;

import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;
import java.util.stream.Stream;

public class DeleteAllService extends BaseService {

	private static DeleteAllService instance;
	private final GetClassRepository getClassRepository;
	private final GetUserToolRepository getUserToolRepository;
	private final EnsureClassDeletionService ensureClassDeletionService;
	private final DeleteUserToolRepository deleteUserToolRepository;

	private DeleteAllService() {
		this.getClassRepository = GetClassRepository.getInstance();
		this.getUserToolRepository = GetUserToolRepository.getInstance();
		this.ensureClassDeletionService = EnsureClassDeletionService.getInstance();
		this.deleteUserToolRepository = DeleteUserToolRepository.getInstance();
	}

	public static synchronized DeleteAllService getInstance() {
		if (instance == null) {
			instance = new DeleteAllService();
		}
		return instance;
	}

	public void deleteAll(Transaction tx, String token) {
		
		validateToken(token);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot delete all nodes without an active Namespace.");
		}
		
		// Delete all Classes (this will also delete other Deposit elements under the Namespace except User Tools and the Archive)
		Stream<Node> classes = getClassRepository.getClass(tx, namespaceName, "");
		classes.forEach(classNode -> {
			String className = String.valueOf(classNode.getProperty("name"));
			ensureClassDeletionService.ensureClassDeletion(tx, timeStamp, username, namespaceName, versioningType, className);
		});
		
		// Delete all User Tools
		Stream<Node> userTools = getUserToolRepository.getUserTool(tx, namespaceName, "");
		userTools.forEach(userTool -> {
			String userToolName = String.valueOf(userTool.getProperty("name"));
			deleteUserToolRepository.deleteUserTool(tx, timeStamp, username, namespaceName, versioningType, userToolName);
		});
	}
}
