package deposit.service;

import deposit.repository.GetObjectClassesRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetObjectClassesService extends BaseService {

	private static GetObjectClassesService instance;
	private final GetObjectClassesRepository getObjectClassesRepository;

	private GetObjectClassesService() {
		this.getObjectClassesRepository = GetObjectClassesRepository.getInstance();
	}

	public static synchronized GetObjectClassesService getInstance() {
		if (instance == null) {
			instance = new GetObjectClassesService();
		}
		return instance;
	}

	public Stream<Node> getObjectClasses(Transaction tx, String objectId, String token) {
		
		validateToken(token);
		validateId(objectId);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve the Classes without an active Namespace.");
		}
		
		return getObjectClassesRepository.getObjectClasses(tx, namespaceName, objectId);
	}
}
