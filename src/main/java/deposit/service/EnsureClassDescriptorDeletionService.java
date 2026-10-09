package deposit.service;

import deposit.repository.DeleteClassDescriptorByIdRepository;
import deposit.repository.GetClassDescriptorRepository;
import deposit.repository.SetClassDescriptorOrderRepository;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class EnsureClassDescriptorDeletionService extends BaseService {

	private static EnsureClassDescriptorDeletionService instance;
	private final DeleteClassDescriptorByIdRepository deleteClassDescriptorByIdRepository;
	private final GetClassDescriptorRepository getClassDescriptorRepository;
	private final SetClassDescriptorOrderRepository setClassDescriptorOrderRepository;

	private EnsureClassDescriptorDeletionService() {
		this.deleteClassDescriptorByIdRepository = DeleteClassDescriptorByIdRepository.getInstance();
		this.getClassDescriptorRepository = GetClassDescriptorRepository.getInstance();
		this.setClassDescriptorOrderRepository = SetClassDescriptorOrderRepository.getInstance();
	}

	public static synchronized EnsureClassDescriptorDeletionService getInstance() {
		if (instance == null) {
			instance = new EnsureClassDescriptorDeletionService();
		}
		return instance;
	}

	public void ensureClassDescriptorDeletion(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String descriptorId) {
		
		String className = deleteClassDescriptorByIdRepository.deleteClassDescriptorById(tx, timeStamp, username, namespaceName, versioningType, descriptorId);
		
		// Update the order of the remaining Class Descriptors after deletion
		List<Node> remainingClassDescriptors = getClassDescriptorRepository.getClassDescriptor(tx, namespaceName, className, "")
			.sorted((n1, n2) -> Long.compare((Long) n1.getProperty("order", 0L), (Long) n2.getProperty("order", 0L)))
			.collect(Collectors.toList());
		Long newOrder = 1L;
		for (Node remainingClassDescriptor : remainingClassDescriptors) {
			String remainingClassDescriptorName = (String) remainingClassDescriptor.getProperty("name");
			setClassDescriptorOrderRepository.setClassDescriptorOrder(tx, timeStamp, username, namespaceName, versioningType, className, remainingClassDescriptorName, newOrder++);
		}
	}
}
