package deposit.service;

import deposit.repository.GetObjectClassDescriptorRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

public class GetObjectClassDescriptorService extends BaseService {

	private static GetObjectClassDescriptorService instance;
	private final GetObjectClassDescriptorRepository getObjectClassDescriptorRepository;

	private GetObjectClassDescriptorService() {
		this.getObjectClassDescriptorRepository = GetObjectClassDescriptorRepository.getInstance();
	}

	public static synchronized GetObjectClassDescriptorService getInstance() {
		if (instance == null) {
			instance = new GetObjectClassDescriptorService();
		}
		return instance;
	}

	public Node getObjectClassDescriptor(Transaction tx, String objectId, String descriptorName, String token) {
		
		validateToken(token);
		validateId(objectId);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve a Class Descriptor without an active Namespace.");
		}
		
		return getObjectClassDescriptorRepository.getObjectClassDescriptor(tx, namespaceName, objectId, descriptorName);
	}
}
