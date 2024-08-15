package deposit.service;

import deposit.repository.GetClassMembersRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetClassMembersService extends BaseService {

	private static GetClassMembersService instance;
	private final GetClassMembersRepository getClassMembersRepository;

	private GetClassMembersService() {
		this.getClassMembersRepository = GetClassMembersRepository.getInstance();
	}

	public static synchronized GetClassMembersService getInstance() {
		if (instance == null) {
			instance = new GetClassMembersService();
		}
		return instance;
	}

	public Stream<Node> getClassMembers(Transaction tx, String className, String token) {
		
		validateToken(token);
		validateId(className);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve the Objects without an active Namespace.");
		}
		
		return getClassMembersRepository.getClassMembers(tx, namespaceName, className);
	}
}
