package deposit.service;

import deposit.repository.GetDescriptorRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetDescriptorService extends BaseService {

	private static GetDescriptorService instance;
	private final GetDescriptorRepository getDescriptorRepository;

	private GetDescriptorService() {
		this.getDescriptorRepository = GetDescriptorRepository.getInstance();
	}

	public static synchronized GetDescriptorService getInstance() {
		if (instance == null) {
			instance = new GetDescriptorService();
		}
		return instance;
	}

	public Stream<Node> getDescriptor(Transaction tx, String objectId, String descriptorName, String token) {
		
		validateToken(token);
		validateId(objectId);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve a Descriptor without an active Namespace.");
		}
		
		return getDescriptorRepository.getDescriptor(tx, namespaceName, objectId, descriptorName);
	}
}
