package deposit.service;

import deposit.repository.SetVersioningTypeRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;

public class SetVersioningTypeService extends BaseService {

	private static SetVersioningTypeService instance;
	private final SetVersioningTypeRepository setVersioningTypeRepository;

	private SetVersioningTypeService() {
		this.setVersioningTypeRepository = SetVersioningTypeRepository.getInstance();
	}

	public static synchronized SetVersioningTypeService getInstance() {
		if (instance == null) {
			instance = new SetVersioningTypeService();
		}
		return instance;
	}

	public void setVersioningType(Transaction tx, String versioningType, String token) {
		
		if (versioningType.equals(ServerContext.getVersioningType(token))) {
			return;
		}
		
		validateToken(token);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot set versioning type without an active Namespace.");
		}
		
		setVersioningTypeRepository.setVersioningType(tx, timeStamp, username, namespaceName, versioningType);
		ServerContext.setVersioningType(versioningType, token);
	}
}
