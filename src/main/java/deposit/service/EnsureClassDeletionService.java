package deposit.service;

import deposit.repository.GetClassMembersRepository;
import deposit.repository.GetObjectClassesRepository;
import deposit.repository.DeleteMembershipRepository;
import deposit.repository.GetClassDescriptorRepository;
import deposit.repository.DeleteClassDescriptorByIdRepository;
import deposit.repository.GetClassRelationshipRepository;
import deposit.repository.DeleteClassRepository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;
import java.util.stream.Stream;

public class EnsureClassDeletionService extends BaseService {

	private static EnsureClassDeletionService instance;
	private final GetClassMembersRepository getClassMembersRepository;
	private final GetObjectClassesRepository getObjectClassesRepository;
	private final DeleteMembershipRepository deleteMembershipRepository;
	private final EnsureObjectDeletionService ensureObjectDeletionService;
	private final GetClassDescriptorRepository getClassDescriptorRepository;
	private final DeleteClassDescriptorByIdRepository deleteClassDescriptorByIdRepository;
	private final GetClassRelationshipRepository getClassRelationshipRepository;
	private final EnsureClassRelationshipDeletionService ensureClassRelationshipDeletionService;
	private final DeleteClassRepository deleteClassRepository;

	private EnsureClassDeletionService() {
		this.getClassMembersRepository = GetClassMembersRepository.getInstance();
		this.getObjectClassesRepository = GetObjectClassesRepository.getInstance();
		this.deleteMembershipRepository = DeleteMembershipRepository.getInstance();
		this.ensureObjectDeletionService = EnsureObjectDeletionService.getInstance();
		this.getClassDescriptorRepository = GetClassDescriptorRepository.getInstance();
		this.deleteClassDescriptorByIdRepository = DeleteClassDescriptorByIdRepository.getInstance();
		this.getClassRelationshipRepository = GetClassRelationshipRepository.getInstance();
		this.ensureClassRelationshipDeletionService = EnsureClassRelationshipDeletionService.getInstance();
		this.deleteClassRepository = DeleteClassRepository.getInstance();
	}

	public static synchronized EnsureClassDeletionService getInstance() {
		if (instance == null) {
			instance = new EnsureClassDeletionService();
		}
		return instance;
	}

	public void ensureClassDeletion(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String className) {
		
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
		relationships.forEach(nodes -> {
			String startClassName = (String) nodes[0].getProperty("name");
			String endClassName = (String) nodes[1].getProperty("name");
			String label = (String) nodes[2].getProperty("label");
			ensureClassRelationshipDeletionService.ensureClassRelationshipDeletion(tx, timeStamp, username, namespaceName, versioningType, startClassName, endClassName, label);
		});

		// Delete the Class node
		deleteClassRepository.deleteClass(tx, timeStamp, username, namespaceName, versioningType, className);
	}
}
