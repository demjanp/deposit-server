package deposit.service;

import deposit.repository.GetClassDescriptorRepository;
import deposit.repository.DeleteClassDescriptorByIdRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

import java.util.Optional;
import java.util.stream.Stream;
import java.time.ZonedDateTime;

public class DeleteClassDescriptorService extends BaseService {

	private static DeleteClassDescriptorService instance;
	private final GetClassDescriptorRepository getClassDescriptorRepository;
	private final DeleteClassDescriptorByIdRepository deleteClassDescriptorByIdRepository;

	private DeleteClassDescriptorService() {
		this.getClassDescriptorRepository = GetClassDescriptorRepository.getInstance();
		this.deleteClassDescriptorByIdRepository = DeleteClassDescriptorByIdRepository.getInstance();
	}

	public static synchronized DeleteClassDescriptorService getInstance() {
		if (instance == null) {
			instance = new DeleteClassDescriptorService();
		}
		return instance;
	}

	public void deleteClassDescriptor(Transaction tx, String className, String descriptorName, String token) {
		
		validateToken(token);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot delete the Class Descriptor without an active Namespace.");
		}
		
		// Get Class Descriptor Id
		Stream<Node> descriptors = getClassDescriptorRepository.getClassDescriptor(tx, namespaceName, className, descriptorName);
		Optional<Node> descriptorNode = descriptors.findFirst();
		if (!descriptorNode.isPresent()) {
			throwException("Error deleting Class Descriptor " + descriptorName + ". It does not exist.");
		}
		String descriptorId = (String) descriptorNode.get().getProperty("id");
		
		deleteClassDescriptorByIdRepository.deleteClassDescriptorById(tx, timeStamp, username, namespaceName, versioningType, descriptorId);
	}
}
