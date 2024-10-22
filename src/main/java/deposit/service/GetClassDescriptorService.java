package deposit.service;

import deposit.repository.GetClassDescriptorRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class GetClassDescriptorService extends BaseService {

	private static GetClassDescriptorService instance;
	private final GetClassDescriptorRepository getClassDescriptorRepository;

	private GetClassDescriptorService() {
		this.getClassDescriptorRepository = GetClassDescriptorRepository.getInstance();
	}

	public static synchronized GetClassDescriptorService getInstance() {
		if (instance == null) {
			instance = new GetClassDescriptorService();
		}
		return instance;
	}

	public Stream<Node> getClassDescriptor(Transaction tx, String className, String descriptorName, String token) {
		
		validateToken(token);
		validateName(className);
		if (!descriptorName.isEmpty()) {
			validateName(descriptorName);
		}
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve a Class Descriptor without an active Namespace.");
		}
		
		return getClassDescriptorRepository.getClassDescriptor(tx, namespaceName, className, descriptorName);
	}
}
