package deposit.service;

import deposit.repository.DeleteUserRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

public class DeleteUserService extends BaseService {

	private static DeleteUserService instance;
	private final DeleteUserRepository deleteUserRepository;

	private DeleteUserService() {
		this.deleteUserRepository = DeleteUserRepository.getInstance();
	}

	public static synchronized DeleteUserService getInstance() {
		if (instance == null) {
			instance = new DeleteUserService();
		}
		return instance;
	}

	public void deleteUser(Transaction tx, String name, String token) {
		
		validateToken(token);
		String username = ServerContext.getActiveUsername(token);
		if (name.equals(username)) {
			throwException("Cannot delete the active User.");
		}
		
		ServerContext.deactivateUser(name);
		deleteUserRepository.deleteUser(tx, name);
	}
}
