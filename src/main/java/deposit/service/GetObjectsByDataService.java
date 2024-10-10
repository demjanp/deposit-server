package deposit.service;

import deposit.repository.GetObjectsByDataRepository;
import deposit.common.GraphSONData;
import deposit.common.ServerContext;
import deposit.common.result.StringMapResult;

import org.neo4j.graphdb.Transaction;

import java.util.ArrayList;
import java.util.List;
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
		
		List<GraphSONData.GraphsonObject> graphSONObjects = new ArrayList<>(GraphSONData.parseData(data).values());

		return new StringMapResult(getObjectsByDataRepository.getObjectsByData(tx, namespaceName, graphSONObjects, matchEmpty));
	}
}
