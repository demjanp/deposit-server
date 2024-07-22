package deposit.service;

import deposit.repository.SetVersioningTypeRepository;
import deposit.common.SessionContext;

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

	public void setVersioningType(Transaction tx, String versioningType) {
		
		if (versioningType.equals(SessionContext.getVersioningType())) {
			return;
		}
		
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = SessionContext.getActiveUsername();
		String namespaceName = SessionContext.getActiveNamespace();
		if (namespaceName == null) {
			throwException("Cannot set versioning type. No active Namespace set.");
		}
		
		setVersioningTypeRepository.setVersioningType(tx, timeStamp, username, namespaceName, versioningType);
		SessionContext.setVersioningType(versioningType);
	}
}
