package deposit.service;

import deposit.repository.GetClassDescriptorRepository;
import deposit.repository.CreateClassDescriptorRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class CreateClassDescriptorService extends BaseService {

	private static CreateClassDescriptorService instance;
	private final GetClassDescriptorRepository getClassDescriptorRepository;
	private final EnsureClassDescriptorOrderService ensureClassDescriptorOrderService;
	private final CreateClassDescriptorRepository createClassDescriptorRepository;

	private CreateClassDescriptorService() {
		this.getClassDescriptorRepository = GetClassDescriptorRepository.getInstance();
		this.ensureClassDescriptorOrderService = EnsureClassDescriptorOrderService.getInstance();
		this.createClassDescriptorRepository = CreateClassDescriptorRepository.getInstance();
	}

	public static synchronized CreateClassDescriptorService getInstance() {
		if (instance == null) {
			instance = new CreateClassDescriptorService();
		}
		return instance;
	}

	public Node createClassDescriptor(Transaction tx, String className, String descriptorName, String type, Long order, String comment, String token) {
		
		validateToken(token);
		validateName(className);
		validateName(descriptorName);
		validateDescriptorTypeName(type);
		
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot create Class Descriptor without an active Namespace.");
		}
		
		List<Node> descriptorNodes = getClassDescriptorRepository.getClassDescriptor(tx, namespaceName, className, "").collect(Collectors.toList());
		
		Long maxOrder = descriptorNodes.stream()
			.map(node -> (Long) node.getProperty("order", 0L))
			.max(Long::compare)
			.orElse(0L);
		
		Node node = createClassDescriptorRepository.createClassDescriptor(tx, timeStamp, username, namespaceName, versioningType, className, descriptorName, type, maxOrder + 1, comment);
		
		if ((order > -1) && (order < maxOrder)) {
			ensureClassDescriptorOrderService.ensureClassDescriptorOrder(tx, timeStamp, username, namespaceName, versioningType, className, descriptorName, order);
		}
		
		return node;
	}
}
