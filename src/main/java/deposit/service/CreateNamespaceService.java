package deposit.service;

import deposit.repository.CreateNamespaceRepository;
import deposit.common.ServerContext;
import deposit.common.DepositInfo;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;

public class CreateNamespaceService extends BaseService {

	private static CreateNamespaceService instance;
	private final CreateNamespaceRepository createNamespaceRepository;

	private CreateNamespaceService() {
		this.createNamespaceRepository = CreateNamespaceRepository.getInstance();
	}

	public static synchronized CreateNamespaceService getInstance() {
		if (instance == null) {
			instance = new CreateNamespaceService();
		}
		return instance;
	}

	public Node createNamespace(Transaction tx, String namespaceName, String description, String versioningType, String token) {
		
		validateToken(token);
		validateName(namespaceName);
		
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String depositVersion = DepositInfo.version;
		
		return createNamespaceRepository.createNamespace(tx, timeStamp, depositVersion, username, namespaceName, description, versioningType);
	}
}
