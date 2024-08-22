package deposit.service;

import deposit.repository.CreateObjectRepository;
import deposit.repository.SetMembershipRepository;
import deposit.repository.CreateDescriptorRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;
import java.util.List;

public class CreateObjectService extends BaseService {

	private static CreateObjectService instance;
	private final CreateObjectRepository createObjectRepository;
	private final EnsureClassService ensureClassService;
	private final SetMembershipRepository setMembershipRepository;
	private final CreateDescriptorRepository createDescriptorRepository;
	
	private CreateObjectService() {
		this.createObjectRepository = CreateObjectRepository.getInstance();
		this.ensureClassService = EnsureClassService.getInstance();
		this.setMembershipRepository = SetMembershipRepository.getInstance();
		this.createDescriptorRepository = CreateDescriptorRepository.getInstance();
	}

	public static synchronized CreateObjectService getInstance() {
		if (instance == null) {
			instance = new CreateObjectService();
		}
		return instance;
	}
	
	public Node createObject(Transaction tx, String className, List<List<Object>> descriptors, List<Double> position, String token) {
		
		validateToken(token);
		if (className.isEmpty()) {
			throwException("Cannot create Object without specifying a Class.");
		}
		validateName(className);
		validateDescriptors(descriptors);
		position = validatePosition(position);
		
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot create Object without an active Namespace.");
		}
		
		Node node = createObjectRepository.createObject(tx, timeStamp, username, namespaceName, versioningType, position);
		String objectId = (String) node.getProperty("id");
		
		ensureClassService.ensureClass(tx, timeStamp, username, namespaceName, versioningType, className);
		setMembershipRepository.setMembership(tx, timeStamp, username, namespaceName, versioningType, className, objectId);
		
		for (List<Object> descriptorList : descriptors) {
			String descriptorName = (String) descriptorList.get(0);
			Object value = descriptorList.get(1);
			createDescriptorRepository.createDescriptor(tx, timeStamp, username, namespaceName, versioningType, objectId, descriptorName, value);
		}
		return node;
	}
}
