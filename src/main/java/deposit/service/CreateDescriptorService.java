package deposit.service;

import deposit.repository.CreateDescriptorRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;

public class CreateDescriptorService extends BaseService {

	private static CreateDescriptorService instance;
	private final CreateDescriptorRepository createDescriptorRepository;

	private CreateDescriptorService() {
		this.createDescriptorRepository = CreateDescriptorRepository.getInstance();
	}

	public static synchronized CreateDescriptorService getInstance() {
		if (instance == null) {
			instance = new CreateDescriptorService();
		}
		return instance;
	}

	public Node createDescriptor(Transaction tx, String objectId, String descriptorName, Object value, String token) {
		
		validateToken(token);
		validateId(objectId);
		validateValue(value);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot create Descriptor without an active Namespace.");
		}
		
		// TODO : If the Object's Class has a Descriptor with the given name, check if the value is of the same type
		// TODO : If the Object's Class doesn't have a Descriptor with the given name, create it
		
		return createDescriptorRepository.createDescriptor(tx, timeStamp, username, namespaceName, versioningType, objectId, descriptorName, value);
	}
}
