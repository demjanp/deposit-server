package deposit.service;

import deposit.repository.GetClassRelationshipByIdRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Relationship;

public class GetClassRelationshipByIdService extends BaseService {

	private static GetClassRelationshipByIdService instance;
	private final GetClassRelationshipByIdRepository getClassRelationshipByIdRepository;

	private GetClassRelationshipByIdService() {
		this.getClassRelationshipByIdRepository = GetClassRelationshipByIdRepository.getInstance();
	}

	public static synchronized GetClassRelationshipByIdService getInstance() {
		if (instance == null) {
			instance = new GetClassRelationshipByIdService();
		}
		return instance;
	}

	public Relationship getClassRelationshipById(Transaction tx, String relationshipId, String token) {
		
		validateToken(token);
		validateId(relationshipId);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve a Class Relationship without an active Namespace.");
		}
		
		return getClassRelationshipByIdRepository.getClassRelationshipById(tx, namespaceName, relationshipId);
	}
}
