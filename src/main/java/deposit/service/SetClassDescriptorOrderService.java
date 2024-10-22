package deposit.service;

import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;

public class SetClassDescriptorOrderService extends BaseService {

	private static SetClassDescriptorOrderService instance;
	private final EnsureClassDescriptorOrderService ensureClassDescriptorOrderService;

	private SetClassDescriptorOrderService() {
		this.ensureClassDescriptorOrderService = EnsureClassDescriptorOrderService.getInstance();
	}

	public static synchronized SetClassDescriptorOrderService getInstance() {
		if (instance == null) {
			instance = new SetClassDescriptorOrderService();
		}
		return instance;
	}

	public void setClassDescriptorOrder(Transaction tx, String className, String descriptorName, Long order, String token) {
		
		validateToken(token);
		validateName(className);
		validateName(descriptorName);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot set Class Descriptor order without an active Namespace.");
		}
		
		ensureClassDescriptorOrderService.ensureClassDescriptorOrder(tx, timeStamp, username, namespaceName, versioningType, className, descriptorName, order);
	}
}
