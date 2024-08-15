package deposit.service;

import deposit.repository.GetDescriptorRepository;
import deposit.repository.DeleteDescriptorByIdRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

import java.util.Optional;
import java.util.stream.Stream;
import java.time.ZonedDateTime;

public class DeleteDescriptorService extends BaseService {

	private static DeleteDescriptorService instance;
	private final GetDescriptorRepository getDescriptorRepository;
	private final DeleteDescriptorByIdRepository deleteDescriptorByIdRepository;

	private DeleteDescriptorService() {
		this.getDescriptorRepository = GetDescriptorRepository.getInstance();
		this.deleteDescriptorByIdRepository = DeleteDescriptorByIdRepository.getInstance();
	}

	public static synchronized DeleteDescriptorService getInstance() {
		if (instance == null) {
			instance = new DeleteDescriptorService();
		}
		return instance;
	}

	public void deleteDescriptor(Transaction tx, String objectId, String descriptorName, String token) {
		
		validateToken(token);
		validateId(objectId);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot delete the Descriptor without an active Namespace.");
		}
		
		// Get Descriptor Id
		Stream<Node> descriptors = getDescriptorRepository.getDescriptor(tx, namespaceName, objectId, descriptorName);
		Optional<Node> descriptorNode = descriptors.findFirst();
		if (!descriptorNode.isPresent()) {
			throwException("Error deleting Descriptor " + descriptorName + ". It does not exist.");
		}
		String descriptorId = (String) descriptorNode.get().getProperty("id");
		
		deleteDescriptorByIdRepository.deleteDescriptorById(tx, timeStamp, username, namespaceName, versioningType, descriptorId);
	}
}
