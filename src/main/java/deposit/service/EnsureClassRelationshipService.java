package deposit.service;

import deposit.repository.CreateClassRelationshipRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;

public class EnsureClassRelationshipService extends BaseService {

	private static EnsureClassRelationshipService instance;
	private final CreateClassRelationshipRepository createClassRelationshipRepository;
	private final EnsureClassService ensureClassService;

	private EnsureClassRelationshipService() {
		this.createClassRelationshipRepository = CreateClassRelationshipRepository.getInstance();
		this.ensureClassService = EnsureClassService.getInstance();
	}

	public static synchronized EnsureClassRelationshipService getInstance() {
		if (instance == null) {
			instance = new EnsureClassRelationshipService();
		}
		return instance;
	}

	public Node ensureClassRelationship(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String startClassName, String endClassName, String label, String comment) {
		ensureClassService.ensureClass(tx, timeStamp, username, namespaceName, versioningType, startClassName);
		ensureClassService.ensureClass(tx, timeStamp, username, namespaceName, versioningType, endClassName);
		return createClassRelationshipRepository.createClassRelationship(tx, timeStamp, username, namespaceName, versioningType, startClassName, endClassName, label, comment);
	}
}
