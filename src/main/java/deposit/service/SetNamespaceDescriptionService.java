package deposit.service;

import deposit.repository.SetNamespaceDescriptionRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;

public class SetNamespaceDescriptionService extends BaseService {

	private static SetNamespaceDescriptionService instance;
	private final SetNamespaceDescriptionRepository setNamespaceDescriptionRepository;

	private SetNamespaceDescriptionService() {
		this.setNamespaceDescriptionRepository = SetNamespaceDescriptionRepository.getInstance();
	}

	public static synchronized SetNamespaceDescriptionService getInstance() {
		if (instance == null) {
			instance = new SetNamespaceDescriptionService();
		}
		return instance;
	}

	public void setNamespaceDescription(Transaction tx, String description, String token) {
		
		validateToken(token);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot set description without an active Namespace.");
		}
		
		setNamespaceDescriptionRepository.setNamespaceDescription(tx, timeStamp, username, namespaceName, description);
	}
}
