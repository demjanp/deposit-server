package deposit.service;

import deposit.repository.GetClassDescriptorByIdRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

public class GetClassDescriptorByIdService extends BaseService {

	private static GetClassDescriptorByIdService instance;
	private final GetClassDescriptorByIdRepository getClassDescriptorByIdRepository;

	private GetClassDescriptorByIdService() {
		this.getClassDescriptorByIdRepository = GetClassDescriptorByIdRepository.getInstance();
	}

	public static synchronized GetClassDescriptorByIdService getInstance() {
		if (instance == null) {
			instance = new GetClassDescriptorByIdService();
		}
		return instance;
	}

	public Node getClassDescriptorById(Transaction tx, String descriptorId, String token) {
		
		validateToken(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve a Class Descriptor without an active Namespace.");
		}
		
		return getClassDescriptorByIdRepository.getClassDescriptorById(tx, namespaceName, descriptorId);
	}
}
