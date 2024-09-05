package deposit.service;

import deposit.repository.GetClassRelationshipByIdRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Relationship;

import java.time.ZonedDateTime;

public class DeleteClassRelationshipByIdService extends BaseService {

	private static DeleteClassRelationshipByIdService instance;
	private final GetClassRelationshipByIdRepository getClassRelationshipByIdRepository;
	private final EnsureClassRelationshipDeletionService ensureClassRelationshipDeletionService;

	private DeleteClassRelationshipByIdService() {
		this.getClassRelationshipByIdRepository = GetClassRelationshipByIdRepository.getInstance();
		this.ensureClassRelationshipDeletionService = EnsureClassRelationshipDeletionService.getInstance();
	}

	public static synchronized DeleteClassRelationshipByIdService getInstance() {
		if (instance == null) {
			instance = new DeleteClassRelationshipByIdService();
		}
		return instance;
	}

	public void deleteClassRelationshipById(Transaction tx, String relationshipId, String token) {
		
		validateToken(token);
		validateId(relationshipId);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot delete a Class Relationship without an active Namespace.");
		}
		
		// Get Class Relationship Id
		Relationship rel = getClassRelationshipByIdRepository.getClassRelationshipById(tx, namespaceName, relationshipId);

		if (rel == null) {
			throw new RuntimeException("Error deleting Class Relationship " + relationshipId + ". It does not exist.");
		}

		// Extract properties from nodes
		String startClassName = (String) rel.getStartNode().getProperty("name");
		String endClassName = (String) rel.getEndNode().getProperty("name");
		String label = (String) rel.getProperty("label");
		ensureClassRelationshipDeletionService.ensureClassRelationshipDeletion(tx, timeStamp, username, namespaceName, versioningType, startClassName, endClassName, label);
	}
}
