package deposit.service;

import deposit.repository.GetUserToolByIdRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

public class GetUserToolByIdService extends BaseService {

	private static GetUserToolByIdService instance;
	private final GetUserToolByIdRepository getUserToolByIdRepository;

	private GetUserToolByIdService() {
		this.getUserToolByIdRepository = GetUserToolByIdRepository.getInstance();
	}

	public static synchronized GetUserToolByIdService getInstance() {
		if (instance == null) {
			instance = new GetUserToolByIdService();
		}
		return instance;
	}

	public Node getUserToolById(Transaction tx, String userToolId, String token) {
		
		validateToken(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve a User Tool without an active Namespace.");
		}
		
		return getUserToolByIdRepository.getUserToolById(tx, namespaceName, userToolId);
	}
}
