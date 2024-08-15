package deposit.service;

import deposit.repository.GetClassByIdRepository;
import deposit.repository.GetClassMembersRepository;
import deposit.repository.GetObjectClassesRepository;
import deposit.repository.DeleteMembershipRepository;
import deposit.repository.GetClassDescriptorRepository;
import deposit.repository.DeleteClassDescriptorByIdRepository;
import deposit.repository.GetClassRelationshipRepository;
import deposit.repository.DeleteClassRelationshipByIdRepository;
import deposit.repository.DeleteClassByIdRepository;

import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;
import java.util.stream.Stream;

public class DeleteClassByIdService extends BaseService {

	private static DeleteClassByIdService instance;
	private final GetClassByIdRepository getClassByIdRepository;
	private final GetClassMembersRepository getClassMembersRepository;
	private final GetObjectClassesRepository getObjectClassesRepository;
	private final DeleteMembershipRepository deleteMembershipRepository;
	private final EnsureObjectDeletionService ensureObjectDeletionService;
	private final GetClassDescriptorRepository getClassDescriptorRepository;
	private final DeleteClassDescriptorByIdRepository deleteClassDescriptorByIdRepository;
	private final GetClassRelationshipRepository getClassRelationshipRepository;
	private final DeleteClassRelationshipByIdRepository deleteClassRelationshipByIdRepository;
	private final DeleteClassByIdRepository deleteClassByIdRepository;

	private DeleteClassByIdService() {
		this.getClassByIdRepository = GetClassByIdRepository.getInstance();
		this.getClassMembersRepository = GetClassMembersRepository.getInstance();
		this.getObjectClassesRepository = GetObjectClassesRepository.getInstance();
		this.deleteMembershipRepository = DeleteMembershipRepository.getInstance();
		this.ensureObjectDeletionService = EnsureObjectDeletionService.getInstance();
		this.getClassDescriptorRepository = GetClassDescriptorRepository.getInstance();
		this.deleteClassDescriptorByIdRepository = DeleteClassDescriptorByIdRepository.getInstance();
		this.getClassRelationshipRepository = GetClassRelationshipRepository.getInstance();
		this.deleteClassRelationshipByIdRepository = DeleteClassRelationshipByIdRepository.getInstance();
		this.deleteClassByIdRepository = DeleteClassByIdRepository.getInstance();
	}

	public static synchronized DeleteClassByIdService getInstance() {
		if (instance == null) {
			instance = new DeleteClassByIdService();
		}
		return instance;
	}

	public void deleteClassById(Transaction tx, String classId, String token) {
		
		validateToken(token);
		validateId(classId);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot delete a Class without an active Namespace.");
		}

		// Get Class name
		Node classNode = getClassByIdRepository.getClassById(tx, namespaceName, classId);
		if (classNode == null) {
			throwException("Error deleting Class " + classId + ". It does not exist.");
		}
		String className = (String) classNode.getProperty("name");
		
		// Delete all Memberships of Objects in this Class and delete all Objects which are members solely of this Class
		Stream<Node> objects = getClassMembersRepository.getClassMembers(tx, namespaceName, className);
		objects.forEach(object -> {
			String objectId = String.valueOf(object.getProperty("id"));
			Stream<Node> classes = getObjectClassesRepository.getObjectClasses(tx, namespaceName, objectId);
			if (classes.count() == 1) {
				ensureObjectDeletionService.ensureObjectDeletion(tx, timeStamp, username, namespaceName, versioningType, objectId);
			} else {
				deleteMembershipRepository.deleteMembership(tx, timeStamp, username, namespaceName, versioningType, className, objectId);
			}
		});
		
		// Delete all Class Descriptors
		Stream<Node> descriptors = getClassDescriptorRepository.getClassDescriptor(tx, namespaceName, className, "");
		descriptors.forEach(descriptor -> {
			String descriptorId = String.valueOf(descriptor.getProperty("id"));
			deleteClassDescriptorByIdRepository.deleteClassDescriptorById(tx, timeStamp, username, namespaceName, versioningType, descriptorId);
		});
		
		// Delete all Class Relationships between this Class and other Classes
		Stream<Node[]> relationships = getClassRelationshipRepository.getClassRelationship(tx, namespaceName, className, "", "");
		relationships.forEach(relationship -> {
			String relationshipId = String.valueOf(relationship[2].getProperty("id"));
			deleteClassRelationshipByIdRepository.deleteClassRelationshipById(tx, timeStamp, username, namespaceName, versioningType, relationshipId);
		});

		// Delete the Class node
		deleteClassByIdRepository.deleteClassById(tx, timeStamp, username, namespaceName, versioningType, classId);
	}
}
