package deposit.service;

import deposit.repository.DeleteDescriptorByIdRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;

public class DeleteDescriptorByIdService extends BaseService {

	private static DeleteDescriptorByIdService instance;
	private final DeleteDescriptorByIdRepository deleteDescriptorByIdRepository;

	private DeleteDescriptorByIdService() {
		this.deleteDescriptorByIdRepository = DeleteDescriptorByIdRepository.getInstance();
	}

	public static synchronized DeleteDescriptorByIdService getInstance() {
		if (instance == null) {
			instance = new DeleteDescriptorByIdService();
		}
		return instance;
	}

	public void deleteDescriptorById(Transaction tx, String descriptorId, String token) {
		
		validateToken(token);
		validateId(descriptorId);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot create a Descriptor without an active Namespace.");
		}
		
		deleteDescriptorByIdRepository.deleteDescriptorById(tx, timeStamp, username, namespaceName, versioningType, descriptorId);
	}
}
