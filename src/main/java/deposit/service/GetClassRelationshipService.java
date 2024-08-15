package deposit.service;

import deposit.repository.GetClassRelationshipRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetClassRelationshipService extends BaseService {

	private static GetClassRelationshipService instance;
	private final GetClassRelationshipRepository getClassRelationshipRepository;

	private GetClassRelationshipService() {
		this.getClassRelationshipRepository = GetClassRelationshipRepository.getInstance();
	}

	public static synchronized GetClassRelationshipService getInstance() {
		if (instance == null) {
			instance = new GetClassRelationshipService();
		}
		return instance;
	}

	public Stream<Node[]> getClassRelationship(Transaction tx, String startClassName, String endClassName, String label, String token) {
		
		if (startClassName == endClassName) {
			throwException("Cannot retrieve a Relationship of a Class with itself.");
		}
		validateToken(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve a Class Relationship without an active Namespace.");
		}
		
		return getClassRelationshipRepository.getClassRelationship(tx, namespaceName, startClassName, endClassName, label);
	}
}
