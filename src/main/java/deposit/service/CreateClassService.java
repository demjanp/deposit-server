package deposit.service;

import deposit.repository.CreateClassRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.List;
import java.time.ZonedDateTime;

public class CreateClassService extends BaseService {

	private static CreateClassService instance;
	private final CreateClassRepository createClassRepository;
	private final CreateClassDescriptorService createClassDescriptorService;

	private CreateClassService() {
		this.createClassRepository = CreateClassRepository.getInstance();
		this.createClassDescriptorService = CreateClassDescriptorService.getInstance();
	}

	public static synchronized CreateClassService getInstance() {
		if (instance == null) {
			instance = new CreateClassService();
		}
		return instance;
	}

	public Node createClass(Transaction tx, String name, Long order, List<Double> position, String comment, List<List<String>> descriptors, String token) {
		
		validateToken(token);
		validateName(name);
		validateClassDescriptors(descriptors);
		position = validatePosition(position);
		
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot create Class without an active Namespace.");
		}
		for (List<String> descriptorList : descriptors) {
			if (descriptorList.size() > 3) {
				throwException("Invalid format of Descriptor list.");
			}
		}
		Node node = createClassRepository.createClass(tx, timeStamp, username, namespaceName, versioningType, name, order, position, comment);
		for (List<String> descriptorList : descriptors) {
			String descriptorName = descriptorList.get(0);
			String type = descriptorList.get(1);
			String descriptorComment = (descriptorList.size() == 3) ? descriptorList.get(2) : "";
			createClassDescriptorService.createClassDescriptor(tx, name, descriptorName, type, (long) -1, descriptorComment, token);
		}
		return node;
	}
}
