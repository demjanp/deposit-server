package deposit.service;

import deposit.repository.GetClassRepository;
import deposit.repository.SetClassOrderRepository;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class EnsureClassOrderService extends BaseService {
	
	private static EnsureClassOrderService instance;
	private final GetClassRepository getClassRepository;
	private final SetClassOrderRepository setClassOrderRepository;
	
	private EnsureClassOrderService() {
		this.getClassRepository = GetClassRepository.getInstance();
		this.setClassOrderRepository = SetClassOrderRepository.getInstance();
	}
	
	public static synchronized EnsureClassOrderService getInstance() {
		if (instance == null) {
			instance = new EnsureClassOrderService();
		}
		return instance;
	}
	
	public void ensureClassOrder(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String className, Long newOrder) {
		List<Node> classNodes = getClassRepository.getClass(tx, namespaceName, "").collect(Collectors.toList());
		List<Node> sortedNodes = classNodes.stream()
				.sorted((n1, n2) -> Long.compare((Long) n1.getProperty("order", 0L), (Long) n2.getProperty("order", 0L)))
				.collect(Collectors.toList());
		Node classToModify = sortedNodes.stream()
				.filter(node -> className.equals(node.getProperty("name")))
				.findFirst()
				.orElse(null);
		if (classToModify == null) {
			throwException("Class not found: " + className);
			return;
		}
		Long maxOrder = (long) sortedNodes.size();
		if (newOrder <= -1 || newOrder > maxOrder) {
			newOrder = maxOrder + 1;
		}
		Long order = 1L;
		boolean classInserted = false;
		for (Node node : sortedNodes) {
			if (order == newOrder && !classInserted) {
				setClassOrderRepository.setClassOrder(tx, timeStamp, username, namespaceName, versioningType, className, order++);
				classInserted = true;
			}
			if (node.equals(classToModify)) {
				continue;
			}
			setClassOrderRepository.setClassOrder(tx, timeStamp, username, namespaceName, versioningType, (String) node.getProperty("name"), order++);
		}
		if (!classInserted) {
			setClassOrderRepository.setClassOrder(tx, timeStamp, username, namespaceName, versioningType, className, order);
		}
	}
}
