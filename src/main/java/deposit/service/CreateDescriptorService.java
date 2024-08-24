package deposit.service;

import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;

public class CreateDescriptorService extends BaseService {

	private static CreateDescriptorService instance;
	private final EnsureDescriptorService ensureDescriptorService;

	private CreateDescriptorService() {
		this.ensureDescriptorService = EnsureDescriptorService.getInstance();
	}

	public static synchronized CreateDescriptorService getInstance() {
		if (instance == null) {
			instance = new CreateDescriptorService();
		}
		return instance;
	}

	public Node createDescriptor(Transaction tx, String objectId, String descriptorName, Object value, String token) {
		
		validateToken(token);
		validateId(objectId);
		validateValue(value);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot create Descriptor without an active Namespace.");
		}
		
		return ensureDescriptorService.ensureDescriptor(tx, timeStamp, username, namespaceName, versioningType, objectId, descriptorName, value, false);
	}
}
