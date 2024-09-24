package deposit.service;

import deposit.repository.ImportDataRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

public class ImportDataService extends BaseService {

	private static ImportDataService instance;
	private final ImportDataRepository importDataRepository;

	private ImportDataService() {
		this.importDataRepository = ImportDataRepository.getInstance();
	}

	public static synchronized ImportDataService getInstance() {
		if (instance == null) {
			instance = new ImportDataService();
		}
		return instance;
	}

	public void importData(Transaction tx, Map<String, Object> data, List<String> unique, Map<String, String> overwrite, boolean matchEmpty, String token) {
		
		validateToken(token);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot import data without an active Namespace.");
		}
		
		importDataRepository.importData(tx, timeStamp, username, namespaceName, versioningType, data, unique, overwrite, matchEmpty);
	}
}
