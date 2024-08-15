package deposit.service;

import deposit.repository.DeleteObjectRepository;
import deposit.repository.GetDescriptorRepository;
import deposit.repository.DeleteDescriptorByIdRepository;
import deposit.repository.GetRelationshipRepository;
import deposit.repository.DeleteRelationshipByIdRepository;
import deposit.repository.GetObjectClassesRepository;
import deposit.repository.DeleteMembershipRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;
import java.util.stream.Stream;

public class DeleteObjectService extends BaseService {

	private static DeleteObjectService instance;
	private final DeleteObjectRepository deleteObjectRepository;
	private final GetDescriptorRepository getDescriptorRepository;
	private final DeleteDescriptorByIdRepository deleteDescriptorByIdRepository;
	private final GetRelationshipRepository getRelationshipRepository;
	private final DeleteRelationshipByIdRepository deleteRelationshipByIdRepository;
	private final GetObjectClassesRepository getObjectClassesRepository;
	private final DeleteMembershipRepository deleteMembershipRepository;

	private DeleteObjectService() {
		this.deleteObjectRepository = DeleteObjectRepository.getInstance();
		this.getDescriptorRepository = GetDescriptorRepository.getInstance();
		this.deleteDescriptorByIdRepository = DeleteDescriptorByIdRepository.getInstance();
		this.getRelationshipRepository = GetRelationshipRepository.getInstance();
		this.deleteRelationshipByIdRepository = DeleteRelationshipByIdRepository.getInstance();
		this.getObjectClassesRepository = GetObjectClassesRepository.getInstance();
		this.deleteMembershipRepository = DeleteMembershipRepository.getInstance();
	}

	public static synchronized DeleteObjectService getInstance() {
		if (instance == null) {
			instance = new DeleteObjectService();
		}
		return instance;
	}

	public void deleteObject(Transaction tx, String objectId, String token) {

		validateToken(token);
		validateId(objectId);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot delete an Object without an active Namespace.");
		}

		Stream<Node> descriptors = getDescriptorRepository.getDescriptor(tx, namespaceName, objectId, "");
		descriptors.forEach(descriptor -> {
			String descriptorId = String.valueOf(descriptor.getProperty("id"));
			deleteDescriptorByIdRepository.deleteDescriptorById(tx, timeStamp, username, namespaceName, versioningType, descriptorId);
		});

		Stream<Node[]> relationships = getRelationshipRepository.getRelationship(tx, namespaceName, objectId, "", "");
		relationships.forEach(relationship -> {
			String relationshipId = String.valueOf(relationship[2].getProperty("id")); // Assuming the relationship node is the third element
			deleteRelationshipByIdRepository.deleteRelationshipById(tx, timeStamp, username, namespaceName, versioningType, relationshipId);
		});
		
		Stream<Node> classes = getObjectClassesRepository.getObjectClasses(tx, namespaceName, objectId);
		classes.forEach(classNode -> {
			String className = String.valueOf(classNode.getProperty("name"));
			deleteMembershipRepository.deleteMembership(tx, timeStamp, username, namespaceName, versioningType, className, objectId);
		});
		
		deleteObjectRepository.deleteObject(tx, timeStamp, username, namespaceName, versioningType, objectId);
	}
}
