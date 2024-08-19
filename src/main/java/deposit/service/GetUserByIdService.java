package deposit.service;

import deposit.repository.GetUserByIdRepository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

public class GetUserByIdService extends BaseService {

	private static GetUserByIdService instance;
	private final GetUserByIdRepository getUserByIdRepository;

	private GetUserByIdService() {
		this.getUserByIdRepository = GetUserByIdRepository.getInstance();
	}

	public static synchronized GetUserByIdService getInstance() {
		if (instance == null) {
			instance = new GetUserByIdService();
		}
		return instance;
	}

	public Node getUserById(Transaction tx, String userId, String token) {
		
		validateToken(token);
		
		return getUserByIdRepository.getUserById(tx, userId);
	}
}
