package deposit.service;

import deposit.repository.SetActiveNamespaceRepository;
import deposit.common.SessionContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;

public class SetActiveNamespaceService {

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

	public void setActiveNamespace(Transaction tx, String name) {

		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = SessionContext.getActiveUsername();
		
		if (setActiveNamespaceRepository.setActiveNamespace(tx, timeStamp, username, name)) {
			SessionContext.setActiveNamespace(name);
		}
	}
}
