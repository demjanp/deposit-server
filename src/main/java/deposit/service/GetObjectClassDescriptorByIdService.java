package deposit.service;

import deposit.repository.GetObjectClassDescriptorByIdRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

public class GetObjectClassDescriptorByIdService extends BaseService {

	private static GetObjectClassDescriptorByIdService instance;
	private final GetObjectClassDescriptorByIdRepository getObjectClassDescriptorByIdRepository;

	private GetObjectClassDescriptorByIdService() {
		this.getObjectClassDescriptorByIdRepository = GetObjectClassDescriptorByIdRepository.getInstance();
	}

	public static synchronized GetObjectClassDescriptorByIdService getInstance() {
		if (instance == null) {
			instance = new GetObjectClassDescriptorByIdService();
		}
		return instance;
	}

	public Node getObjectClassDescriptorById(Transaction tx, String descriptorId, String token) {
		
		validateToken(token);
		validateId(descriptorId);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve a Class Descriptor without an active Namespace.");
		}
		
		return getObjectClassDescriptorByIdRepository.getObjectClassDescriptorById(tx, namespaceName, descriptorId);
	}
}
