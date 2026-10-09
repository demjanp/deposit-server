package deposit.service;

import deposit.repository.UtilsDownloadRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

public class UtilsDownloadService extends BaseService {

	private static UtilsDownloadService instance;
	private final UtilsDownloadRepository utilsDownloadRepository;

	private UtilsDownloadService() {
		this.utilsDownloadRepository = UtilsDownloadRepository.getInstance();
	}

	public static synchronized UtilsDownloadService getInstance() {
		if (instance == null) {
			instance = new UtilsDownloadService();
		}
		return instance;
	}

	public String utilsDownload(Transaction tx, Object descriptor, String token) {
		
		validateToken(token);
		String descriptorId = "";
		if (descriptor instanceof Node) {
			Node node = (Node) descriptor;
			if (node.getLabels().iterator().hasNext()) {
				Label firstLabel = node.getLabels().iterator().next();
				if ("Descriptor".equals(firstLabel.name())) {
					 descriptorId = node.getProperty("id").toString();
				}
			}
		} else if (descriptor instanceof String) {
			descriptorId = (String) descriptor;
		}
		if (descriptorId.isEmpty()) {
			throwException("Invalid value specified as descriptor: " + descriptor);
		}
		String namespaceName = ServerContext.getActiveNamespace(token);
		if (namespaceName == null) {
			throwException("Cannot download a resource without an active Namespace.");
		}
		
		return utilsDownloadRepository.utilsDownload(tx, namespaceName, descriptorId);
	}
}
