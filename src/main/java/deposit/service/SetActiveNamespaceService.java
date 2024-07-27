package deposit.service;

import deposit.repository.SetActiveNamespaceRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;

public class SetActiveNamespaceService extends BaseService {

	private static SetActiveNamespaceService instance;
	private final SetActiveNamespaceRepository setActiveNamespaceRepository;

	private SetActiveNamespaceService() {
		this.setActiveNamespaceRepository = SetActiveNamespaceRepository.getInstance();
	}

	public static synchronized SetActiveNamespaceService getInstance() {
		if (instance == null) {
			instance = new SetActiveNamespaceService();
		}
		return instance;
	}

	public void setActiveNamespace(Transaction tx, String name, String token) {
		
		validateToken(token);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		
		Node namespace = setActiveNamespaceRepository.setActiveNamespace(tx, timeStamp, username, name);
		if (namespace != null) {
			String versioningType = (String) namespace.getProperty("versioningType");
			ServerContext.setActiveNamespace(name, token);
			ServerContext.setVersioningType(versioningType, token);
		}
	}
}
