package deposit.service;

import deposit.repository.GetClassByIdRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

public class GetClassByIdService extends BaseService {

	private static GetClassByIdService instance;
	private final GetClassByIdRepository getClassByIdRepository;

	private GetClassByIdService() {
		this.getClassByIdRepository = GetClassByIdRepository.getInstance();
	}

	public static synchronized GetClassByIdService getInstance() {
		if (instance == null) {
			instance = new GetClassByIdService();
		}
		return instance;
	}

	public Node getClassById(Transaction tx, String classId, String token) {
		
		validateToken(token);
		validateId(classId);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve a Class without an active Namespace.");
		}
		
		return getClassByIdRepository.getClassById(tx, namespaceName, classId);
	}
}
