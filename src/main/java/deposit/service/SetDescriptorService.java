package deposit.service;

import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;

public class SetDescriptorService extends BaseService {

	private static SetDescriptorService instance;
	private final EnsureDescriptorService ensureDescriptorService;

	private SetDescriptorService() {
		this.ensureDescriptorService = EnsureDescriptorService.getInstance();
	}

	public static synchronized SetDescriptorService getInstance() {
		if (instance == null) {
			instance = new SetDescriptorService();
		}
		return instance;
	}

	public Node setDescriptor(Transaction tx, String objectId, String descriptorName, Object value, String token) {
		
		validateToken(token);
		validateId(objectId);
		validateName(descriptorName);
		validateValue(value);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot set Descriptor without an active Namespace.");
		}
		
		return ensureDescriptorService.ensureDescriptor(tx, timeStamp, username, namespaceName, versioningType, objectId, descriptorName, value, true);
	}
}
