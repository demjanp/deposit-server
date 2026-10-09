package deposit.service;

import deposit.repository.GetUserToolRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetUserToolService extends BaseService {

	private static GetUserToolService instance;
	private final GetUserToolRepository getUserToolRepository;

	private GetUserToolService() {
		this.getUserToolRepository = GetUserToolRepository.getInstance();
	}

	public static synchronized GetUserToolService getInstance() {
		if (instance == null) {
			instance = new GetUserToolService();
		}
		return instance;
	}

	public Stream<Node> getUserTool(Transaction tx, String userToolName, String token) {
		
		validateToken(token);
		validateName(userToolName);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve a User Tool without an active Namespace.");
		}
		
		return getUserToolRepository.getUserTool(tx, namespaceName, userToolName);
	}
}
