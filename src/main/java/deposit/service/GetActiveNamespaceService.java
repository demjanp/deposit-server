package deposit.service;

import deposit.common.SessionContext;

import org.neo4j.graphdb.Transaction;

public class GetActiveNamespaceService {

	private static GetActiveNamespaceService instance;

	private GetActiveNamespaceService() {}

	public static synchronized GetActiveNamespaceService getInstance() {
		if (instance == null) {
			instance = new GetActiveNamespaceService();
		}
		return instance;
	}

	public String getActiveNamespace(Transaction tx) {
		return SessionContext.getActiveNamespace();
	}
}
