package deposit.service;

import deposit.service.data.DataHandler;
import deposit.repository.GetObjectsByDataRepository;
import deposit.common.ServerContext;
import deposit.common.result.StringMapResult;

import org.neo4j.graphdb.Transaction;

import java.util.Map;

public class GetObjectsByDataService extends BaseService {

	private static GetObjectsByDataService instance;
	private final GetObjectsByDataRepository getObjectsByDataRepository;

	private GetObjectsByDataService() {
		this.getObjectsByDataRepository = GetObjectsByDataRepository.getInstance();
	}

	public static synchronized GetObjectsByDataService getInstance() {
		if (instance == null) {
			instance = new GetObjectsByDataService();
		}
		return instance;
	}

	public StringMapResult getObjectsByData(Transaction tx, Map<String, Object> data, boolean matchEmpty, String token) {
		
		validateToken(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot retrieve Objects without an active Namespace.");
		}
		
		DataHandler.DataMatchQuery result = DataHandler.buildDataMatchQuery(data, matchEmpty);
		return getObjectsByDataRepository.getObjectsByData(tx, namespaceName, result.getQueryStr(), result.getValues());
	}
}
