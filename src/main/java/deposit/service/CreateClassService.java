package deposit.service;

import deposit.repository.GetClassRepository;
import deposit.repository.CreateClassRepository;
import deposit.repository.CreateClassDescriptorRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.List;
import java.util.stream.Collectors;
import java.time.ZonedDateTime;

public class CreateClassService extends BaseService {

	private static CreateClassService instance;
	private final GetClassRepository getClassRepository;
	private final CreateClassRepository createClassRepository;
	private final EnsureClassOrderService ensureClassOrderService;
	private final CreateClassDescriptorRepository createClassDescriptorRepository;

	private CreateClassService() {
		this.getClassRepository = GetClassRepository.getInstance();
		this.createClassRepository = CreateClassRepository.getInstance();
		this.ensureClassOrderService = EnsureClassOrderService.getInstance();
		this.createClassDescriptorRepository = CreateClassDescriptorRepository.getInstance();
	}

	public static synchronized CreateClassService getInstance() {
		if (instance == null) {
			instance = new CreateClassService();
		}
		return instance;
	}

	public Node createClass(Transaction tx, String className, Long order, List<Double> position, String comment, List<List<String>> descriptors, String token) {

		validateToken(token);
		validateName(className);
		validateClassDescriptors(descriptors);
		position = validatePosition(position);

		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot create Class without an active Namespace.");
		}

		List<Node> classNodes = getClassRepository.getClass(tx, namespaceName, "").collect(Collectors.toList());
		
		Long maxOrder = classNodes.stream()
			.map(node -> (Long) node.getProperty("order", 0L))
			.max(Long::compare)
			.orElse(0L);

		Node node = createClassRepository.createClass(tx, timeStamp, username, namespaceName, versioningType, className, maxOrder + 1, position, comment);
		
		if ((order > -1) && (order < maxOrder)) {
			ensureClassOrderService.ensureClassOrder(tx, timeStamp, username, namespaceName, versioningType, className, order);
		}
		
		// Create the descriptors associated with the class
		Long descrOrder = 1L;
		for (List<String> descriptorList : descriptors) {
			String descriptorName = descriptorList.get(0);
			String type = descriptorList.get(1);
			String descriptorComment = (descriptorList.size() == 3) ? descriptorList.get(2) : "";
			createClassDescriptorRepository.createClassDescriptor(tx, timeStamp, username, namespaceName, versioningType, className, descriptorName, type, descrOrder, descriptorComment);
			descrOrder++;
		}

		return node;
	}
}
