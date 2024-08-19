package deposit.service;

import deposit.repository.GetUserRepository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetUserService extends BaseService {

	private static GetUserService instance;
	private final GetUserRepository getUserRepository;

	private GetUserService() {
		this.getUserRepository = GetUserRepository.getInstance();
	}

	public static synchronized GetUserService getInstance() {
		if (instance == null) {
			instance = new GetUserService();
		}
		return instance;
	}

	public Stream<Node> getUser(Transaction tx, String username, String token) {
		
		validateToken(token);
		return getUserRepository.getUser(tx, username);
	}
}
