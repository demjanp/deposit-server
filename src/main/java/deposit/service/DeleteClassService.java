package deposit.service;

import deposit.repository.DeleteClassRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;

public class DeleteClassService extends BaseService {

	private static DeleteClassService instance;
	private final DeleteClassRepository deleteClassRepository;

	private DeleteClassService() {
		this.deleteClassRepository = DeleteClassRepository.getInstance();
	}

	public static synchronized DeleteClassService getInstance() {
		if (instance == null) {
			instance = new DeleteClassService();
		}
		return instance;
	}

	public void deleteClass(Transaction tx, String className, String token) {
		
		validateToken(token);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot create Class without an active Namespace.");
		}
		
		deleteClassRepository.deleteClass(tx, timeStamp, username, namespaceName, versioningType, className);
	}
}
