package deposit.service;

import deposit.repository.DeleteObjectRepository;
import deposit.repository.GetDescriptorRepository;
import deposit.repository.DeleteDescriptorByIdRepository;
import deposit.repository.GetRelationshipRepository;
import deposit.repository.DeleteRelationshipByIdRepository;
import deposit.repository.GetObjectClassRepository;
import deposit.repository.DeleteMembershipRepository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;

import java.time.ZonedDateTime;
import java.util.stream.Stream;

public class EnsureObjectDeletionService extends BaseService {

	private static EnsureObjectDeletionService instance;
	private final DeleteObjectRepository deleteObjectRepository;
	private final GetDescriptorRepository getDescriptorRepository;
	private final DeleteDescriptorByIdRepository deleteDescriptorByIdRepository;
	private final GetRelationshipRepository getRelationshipRepository;
	private final DeleteRelationshipByIdRepository deleteRelationshipByIdRepository;
	private final GetObjectClassRepository getObjectClassRepository;
	private final DeleteMembershipRepository deleteMembershipRepository;

	private EnsureObjectDeletionService() {
		this.deleteObjectRepository = DeleteObjectRepository.getInstance();
		this.getDescriptorRepository = GetDescriptorRepository.getInstance();
		this.deleteDescriptorByIdRepository = DeleteDescriptorByIdRepository.getInstance();
		this.getRelationshipRepository = GetRelationshipRepository.getInstance();
		this.deleteRelationshipByIdRepository = DeleteRelationshipByIdRepository.getInstance();
		this.getObjectClassRepository = GetObjectClassRepository.getInstance();
		this.deleteMembershipRepository = DeleteMembershipRepository.getInstance();
	}

	public static synchronized EnsureObjectDeletionService getInstance() {
		if (instance == null) {
			instance = new EnsureObjectDeletionService();
		}
		return instance;
	}
	
	public void ensureObjectDeletion(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String objectId) {
		
		Stream<Node> descriptors = getDescriptorRepository.getDescriptor(tx, namespaceName, objectId, "");
		descriptors.forEach(descriptor -> {
			String descriptorId = String.valueOf(descriptor.getProperty("id"));
			deleteDescriptorByIdRepository.deleteDescriptorById(tx, timeStamp, username, namespaceName, versioningType, descriptorId);
		});

		Stream<Relationship> rels = getRelationshipRepository.getRelationship(tx, namespaceName, objectId, "", "");
		rels.forEach(rel -> {
			String relationshipId = String.valueOf(rel.getProperty("id"));
			deleteRelationshipByIdRepository.deleteRelationshipById(tx, timeStamp, username, namespaceName, versioningType, relationshipId);
		});
		
		Node classNode = getObjectClassRepository.getObjectClass(tx, namespaceName, objectId);
		String className = String.valueOf(classNode.getProperty("name"));
		deleteMembershipRepository.deleteMembership(tx, timeStamp, username, namespaceName, versioningType, className, objectId);
		
		deleteObjectRepository.deleteObject(tx, timeStamp, username, namespaceName, versioningType, objectId);
	}
}
