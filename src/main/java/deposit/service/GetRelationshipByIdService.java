package deposit.service;

import deposit.repository.GetRelationshipByIdRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Relationship;

public class GetRelationshipByIdService extends BaseService {

	private static GetRelationshipByIdService instance;
	private final GetRelationshipByIdRepository getRelationshipByIdRepository;

	private GetRelationshipByIdService() {
		this.getRelationshipByIdRepository = GetRelationshipByIdRepository.getInstance();
	}

	public static synchronized GetRelationshipByIdService getInstance() {
		if (instance == null) {
			instance = new GetRelationshipByIdService();
		}
		return instance;
	}

	public Relationship getRelationshipById(Transaction tx, String relationshipId, String token) {
		
		validateToken(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve a Relationship without an active Namespace.");
		}
		
		return getRelationshipByIdRepository.getRelationshipById(tx, namespaceName, relationshipId);
	}
}
