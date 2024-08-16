package deposit.service;

import deposit.repository.GetRelationshipRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetRelationshipService extends BaseService {

	private static GetRelationshipService instance;
	private final GetRelationshipRepository getRelationshipRepository;

	private GetRelationshipService() {
		this.getRelationshipRepository = GetRelationshipRepository.getInstance();
	}

	public static synchronized GetRelationshipService getInstance() {
		if (instance == null) {
			instance = new GetRelationshipService();
		}
		return instance;
	}

	public Stream<Node[]> getRelationship(Transaction tx, String startObjectId, String endObjectId, String label, String token) {
		
		if (startObjectId.equals(endObjectId)) {
			throwException("Cannot retrieve a Relationship of an Object with itself.");
		}
		validateToken(token);
		validateId(startObjectId);
		if (!endObjectId.isEmpty()) {
			validateId(endObjectId);
		}
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve a Relationship without an active Namespace.");
		}
		
		return getRelationshipRepository.getRelationship(tx, namespaceName, startObjectId, endObjectId, label);
	}
}
