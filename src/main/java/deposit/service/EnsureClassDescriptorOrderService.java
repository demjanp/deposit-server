package deposit.service;

import deposit.repository.GetClassDescriptorRepository;
import deposit.repository.SetClassDescriptorOrderRepository;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class EnsureClassDescriptorOrderService extends BaseService {
	
	private static EnsureClassDescriptorOrderService instance;
	private final GetClassDescriptorRepository getClassDescriptorRepository;
	private final SetClassDescriptorOrderRepository setClassDescriptorOrderRepository;
	
	private EnsureClassDescriptorOrderService() {
		this.getClassDescriptorRepository = GetClassDescriptorRepository.getInstance();
		this.setClassDescriptorOrderRepository = SetClassDescriptorOrderRepository.getInstance();
	}
	
	public static synchronized EnsureClassDescriptorOrderService getInstance() {
		if (instance == null) {
			instance = new EnsureClassDescriptorOrderService();
		}
		return instance;
	}
	
	public void ensureClassDescriptorOrder(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String className, String descriptorName, Long newOrder) {
		List<Node> descriptorNodes = getClassDescriptorRepository.getClassDescriptor(tx, namespaceName, className, "").collect(Collectors.toList());
		List<Node> sortedNodes = descriptorNodes.stream()
				.sorted((n1, n2) -> Long.compare((Long) n1.getProperty("order", 0L), (Long) n2.getProperty("order", 0L)))
				.collect(Collectors.toList());
		Node descriptorToModify = sortedNodes.stream()
				.filter(node -> descriptorName.equals(node.getProperty("name")))
				.findFirst()
				.orElse(null);
		if (descriptorToModify == null) {
			throwException("Class Descriptor not found: " + descriptorName);
			return;
		}
		Long maxOrder = (long) sortedNodes.size();
		if (newOrder <= -1 || newOrder > maxOrder) {
			newOrder = maxOrder + 1;
		}
		Long order = 1L;
		boolean descriptorInserted = false;
		for (Node node : sortedNodes) {
			if (order == newOrder && !descriptorInserted) {
				setClassDescriptorOrderRepository.setClassDescriptorOrder(tx, timeStamp, username, namespaceName, versioningType, className, descriptorName, order++);
				descriptorInserted = true;
			}
			if (node.equals(descriptorToModify)) {
				continue;
			}
			setClassDescriptorOrderRepository.setClassDescriptorOrder(tx, timeStamp, username, namespaceName, versioningType, className, (String) node.getProperty("name"), order++);
		}
		if (!descriptorInserted) {
			setClassDescriptorOrderRepository.setClassDescriptorOrder(tx, timeStamp, username, namespaceName, versioningType, className, descriptorName, order);
		}
	}
}
