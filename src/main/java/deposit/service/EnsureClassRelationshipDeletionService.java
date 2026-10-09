package deposit.service;

import deposit.repository.EnsureClassRelationshipDeletionRepository;
import deposit.repository.DeleteRelationshipByIdRepository;
import deposit.repository.DeleteClassRelationshipRepository;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;
import java.util.stream.Stream;

public class EnsureClassRelationshipDeletionService extends BaseService {

	private static EnsureClassRelationshipDeletionService instance;
	private final EnsureClassRelationshipDeletionRepository ensureClassRelationshipDeletionRepository;
	private final DeleteRelationshipByIdRepository deleteRelationshipByIdRepository;
	private final DeleteClassRelationshipRepository deleteClassRelationshipRepository;

	private EnsureClassRelationshipDeletionService() {
		this.ensureClassRelationshipDeletionRepository = EnsureClassRelationshipDeletionRepository.getInstance();
		this.deleteRelationshipByIdRepository = DeleteRelationshipByIdRepository.getInstance();
		this.deleteClassRelationshipRepository = DeleteClassRelationshipRepository.getInstance();
	}

	public static synchronized EnsureClassRelationshipDeletionService getInstance() {
		if (instance == null) {
			instance = new EnsureClassRelationshipDeletionService();
		}
		return instance;
	}

	public void ensureClassRelationshipDeletion(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String startClassName, String endClassName, String label) {
		
		// Delete Relationships with the same label between all member Objects of the Classes
		Stream<String> ids = ensureClassRelationshipDeletionRepository.getObjectRelationshipIds(tx, namespaceName, startClassName, endClassName, label);
		ids.forEach(relationshipId -> {
			deleteRelationshipByIdRepository.deleteRelationshipById(tx, timeStamp, username, namespaceName, versioningType, relationshipId);
		});
		
		deleteClassRelationshipRepository.deleteClassRelationship(tx, timeStamp, username, namespaceName, versioningType, startClassName, endClassName, label);
	}
}
