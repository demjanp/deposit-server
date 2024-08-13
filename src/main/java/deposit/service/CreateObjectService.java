package deposit.service;

import deposit.repository.CreateObjectRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;
import java.util.List;

public class CreateObjectService extends BaseService {

	private static CreateObjectService instance;
	private final CreateObjectRepository createObjectRepository;
	private final CreateMembershipService createMembershipService;
	private final CreateDescriptorService createDescriptorService;

	private CreateObjectService() {
		this.createObjectRepository = CreateObjectRepository.getInstance();
		this.createMembershipService = CreateMembershipService.getInstance();
		this.createDescriptorService = CreateDescriptorService.getInstance();
	}

	public static synchronized CreateObjectService getInstance() {
		if (instance == null) {
			instance = new CreateObjectService();
		}
		return instance;
	}
	
	public Node createObject(Transaction tx, List<String> classes, List<List<Object>> descriptors, List<Double> position, String token) {
		
		validateToken(token);
		if (classes == null || classes.isEmpty()) {
			throwException("Cannot create Object without specifying at least one Class.");
		}
		for (String className : classes) {
			validateName(className);
		}
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
		for (String className : classes) {
			createMembershipService.createMembership(tx, className, objectId, token);
		}
		for (List<Object> descriptorList : descriptors) {
			String descriptorName = (String) descriptorList.get(0);
			Object value = descriptorList.get(1);
			createDescriptorService.createDescriptor(tx, objectId, descriptorName, value, token);
		}
		return node;
	}
}
