package deposit.service;

import deposit.repository.DeleteClassDescriptorByIdRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;

public class DeleteClassDescriptorByIdService extends BaseService {

	private static DeleteClassDescriptorByIdService instance;
	private final DeleteClassDescriptorByIdRepository deleteClassDescriptorByIdRepository;

	private DeleteClassDescriptorByIdService() {
		this.deleteClassDescriptorByIdRepository = DeleteClassDescriptorByIdRepository.getInstance();
	}

	public static synchronized DeleteClassDescriptorByIdService getInstance() {
		if (instance == null) {
			instance = new DeleteClassDescriptorByIdService();
		}
		return instance;
	}

	public void deleteClassDescriptorById(Transaction tx, String descriptorId, String token) {
		
		validateToken(token);
		validateId(descriptorId);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot create a Class Descriptor without an active Namespace.");
		}
		
		deleteClassDescriptorByIdRepository.deleteClassDescriptorById(tx, timeStamp, username, namespaceName, versioningType, descriptorId);
	}
}
