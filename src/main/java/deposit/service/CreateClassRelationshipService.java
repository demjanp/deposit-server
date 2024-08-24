package deposit.service;

import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;

public class CreateClassRelationshipService extends BaseService {

	private static CreateClassRelationshipService instance;
	private final EnsureClassRelationshipService ensureClassRelationshipService;

	private CreateClassRelationshipService() {
		this.ensureClassRelationshipService = EnsureClassRelationshipService.getInstance();
	}

	public static synchronized CreateClassRelationshipService getInstance() {
		if (instance == null) {
			instance = new CreateClassRelationshipService();
		}
		return instance;
	}

	public Node createClassRelationship(Transaction tx, String startClassName, String endClassName, String label, String comment, String token) {
		if (startClassName == endClassName) {
			throwException("Cannot create a Class Relationship within the same Class.");
		}
		validateToken(token);
		validateName(startClassName);
		validateName(endClassName);
		validateName(label);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot create a Class Relationship without an active Namespace.");
		}
		
		return ensureClassRelationshipService.ensureClassRelationship(tx, timeStamp, username, namespaceName, versioningType, startClassName, endClassName, label, comment);
	}
}
