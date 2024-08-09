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
			throwException("Cannot create Class Descriptor without an active Namespace.");
		}
		
		// TODO : If value is a resource, the file is moved from the 'loaded_resources' directory to the permanent file storage directory. The filename remains in the format `<filename>-<uuid>.<ext>`, where `uuid` is generated when the file is uploaded to the server via the deposit.resource procedure.
		
		return createDescriptorRepository.createDescriptor(tx, versioningType, timeStamp, username, namespaceName, objectId, descriptorName, value);
	}
}
