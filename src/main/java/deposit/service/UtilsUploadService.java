package deposit.service;

import deposit.repository.UtilsUploadRepository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

public class UtilsUploadService extends BaseService {

	private static UtilsUploadService instance;
	private final UtilsUploadRepository utilsUploadRepository;

	private UtilsUploadService() {
		this.utilsUploadRepository = UtilsUploadRepository.getInstance();
	}

	public static synchronized UtilsUploadService getInstance() {
		if (instance == null) {
			instance = new UtilsUploadService();
		}
		return instance;
	}

	public Node utilsResource(Transaction tx, String stream, String filename, String token) {
		validateToken(token);
		validateFilename(filename);
		
		return utilsUploadRepository.utilsResource(tx, stream, filename);
	}
}
