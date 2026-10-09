package deposit.service;

import deposit.repository.CreateUserToolRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;

public class CreateUserToolService extends BaseService {

	private static CreateUserToolService instance;
	private final CreateUserToolRepository createUserToolRepository;

	private CreateUserToolService() {
		this.createUserToolRepository = CreateUserToolRepository.getInstance();
	}

	public static synchronized CreateUserToolService getInstance() {
		if (instance == null) {
			instance = new CreateUserToolService();
		}
		return instance;
	}

	public Node createUserTool(Transaction tx, String userToolName, String data, String comment, String token) {
		validateToken(token);
		validateName(userToolName);
		validateJSONString(data);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot create a User Tool without an active Namespace.");
		}

		return createUserToolRepository.createUserTool(tx, timeStamp, username, namespaceName, versioningType, userToolName, data, comment);
	}
}
