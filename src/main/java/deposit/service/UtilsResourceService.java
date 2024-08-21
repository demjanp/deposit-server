package deposit.service;

import deposit.repository.UtilsResourceRepository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

public class UtilsResourceService extends BaseService {

	private static UtilsResourceService instance;
	private final UtilsResourceRepository utilsResourceRepository;

	private UtilsResourceService() {
		this.utilsResourceRepository = UtilsResourceRepository.getInstance();
	}

	public static synchronized UtilsResourceService getInstance() {
		if (instance == null) {
			instance = new UtilsResourceService();
		}
		return instance;
	}

	public Node utilsResource(Transaction tx, String stream, String filename, String token) {
		validateToken(token);
		validateFilename(filename);
		
		return utilsResourceRepository.utilsResource(tx, stream, filename);
	}
}
