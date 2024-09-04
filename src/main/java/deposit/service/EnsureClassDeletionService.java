package deposit.service;

import deposit.repository.GetClassMembersRepository;
import deposit.repository.GetClassDescriptorRepository;
import deposit.repository.DeleteClassDescriptorByIdRepository;
import deposit.repository.GetClassRelationshipRepository;
import deposit.repository.DeleteClassRepository;
import deposit.repository.GetClassRepository;
import deposit.repository.SetClassOrderRepository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class EnsureClassDeletionService extends BaseService {

	private static EnsureClassDeletionService instance;
	private final GetClassMembersRepository getClassMembersRepository;
	private final EnsureObjectDeletionService ensureObjectDeletionService;
	private final GetClassDescriptorRepository getClassDescriptorRepository;
	private final DeleteClassDescriptorByIdRepository deleteClassDescriptorByIdRepository;
	private final GetClassRelationshipRepository getClassRelationshipRepository;
	private final EnsureClassRelationshipDeletionService ensureClassRelationshipDeletionService;
	private final DeleteClassRepository deleteClassRepository;
	private final GetClassRepository getClassRepository;
	private final SetClassOrderRepository setClassOrderRepository;

	private EnsureClassDeletionService() {
		this.getClassMembersRepository = GetClassMembersRepository.getInstance();
		this.ensureObjectDeletionService = EnsureObjectDeletionService.getInstance();
		this.getClassDescriptorRepository = GetClassDescriptorRepository.getInstance();
		this.deleteClassDescriptorByIdRepository = DeleteClassDescriptorByIdRepository.getInstance();
		this.getClassRelationshipRepository = GetClassRelationshipRepository.getInstance();
		this.ensureClassRelationshipDeletionService = EnsureClassRelationshipDeletionService.getInstance();
		this.deleteClassRepository = DeleteClassRepository.getInstance();
		this.getClassRepository = GetClassRepository.getInstance();
		this.setClassOrderRepository = SetClassOrderRepository.getInstance();
	}

	public static synchronized EnsureClassDeletionService getInstance() {
		if (instance == null) {
			instance = new EnsureClassDeletionService();
		}
		return instance;
	}

	public void ensureClassDeletion(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String className) {
		
		// Delete all Objects which are members of this Class
		Stream<Node> objects = getClassMembersRepository.getClassMembers(tx, namespaceName, className);
		objects.forEach(object -> {
			String objectId = String.valueOf(object.getProperty("id"));
			ensureObjectDeletionService.ensureObjectDeletion(tx, timeStamp, username, namespaceName, versioningType, objectId);
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
		
		// Update the order of the remaining Classes after deletion
		List<Node> remainingClasses = getClassRepository.getClass(tx, namespaceName, "")
			.sorted((n1, n2) -> Long.compare((Long) n1.getProperty("order", 0L), (Long) n2.getProperty("order", 0L)))
			.collect(Collectors.toList());
		Long newOrder = 1L;
		for (Node remainingClass : remainingClasses) {
			String remainingClassName = (String) remainingClass.getProperty("name");
			setClassOrderRepository.setClassOrder(tx, timeStamp, username, namespaceName, versioningType, remainingClassName, newOrder++);
		}
	}
}
