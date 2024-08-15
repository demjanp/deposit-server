package deposit.service;

import deposit.repository.GetDescriptorByIdRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

public class GetDescriptorByIdService extends BaseService {

	private static GetDescriptorByIdService instance;
	private final GetDescriptorByIdRepository getDescriptorByIdRepository;

	private GetDescriptorByIdService() {
		this.getDescriptorByIdRepository = GetDescriptorByIdRepository.getInstance();
	}

	public static synchronized GetDescriptorByIdService getInstance() {
		if (instance == null) {
			instance = new GetDescriptorByIdService();
		}
		return instance;
	}

	public Node getDescriptorById(Transaction tx, String descriptorId, String token) {
		
		validateToken(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve a Descriptor without an active Namespace.");
		}
		
		return getDescriptorByIdRepository.getDescriptorById(tx, namespaceName, descriptorId);
	}
}
