package deposit.service;

import deposit.repository.GetClassRelationshipRepository;
import deposit.repository.SetClassRelationshipCommentRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;
import java.util.Optional;

public class SetClassRelationshipService extends BaseService {

	private static SetClassRelationshipService instance;
	private final GetClassRelationshipRepository getClassRelationshipRepository;
	private final SetClassRelationshipCommentRepository setClassRelationshipCommentRepository;
	private final EnsureClassRelationshipService ensureClassRelationshipService;

	private SetClassRelationshipService() {
		this.getClassRelationshipRepository = GetClassRelationshipRepository.getInstance();
		this.setClassRelationshipCommentRepository = SetClassRelationshipCommentRepository.getInstance();
		this.ensureClassRelationshipService = EnsureClassRelationshipService.getInstance();
	}

	public static synchronized SetClassRelationshipService getInstance() {
		if (instance == null) {
			instance = new SetClassRelationshipService();
		}
		return instance;
	}

	public Node setClassRelationship(Transaction tx, String startClassName, String endClassName, String label, String comment, String token) {
		if (startClassName == endClassName) {
			throwException("Cannot update a Class Relationship within the same Class.");
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
			throwException("Cannot update a Class Relationship without an active Namespace.");
		}
		
		Optional<Node[]> result = getClassRelationshipRepository.getClassRelationship(tx, namespaceName, startClassName, endClassName, label).findAny();
		if (result.isEmpty()) {
			return ensureClassRelationshipService.ensureClassRelationship(tx, timeStamp, username, namespaceName, versioningType, startClassName, endClassName, label, comment);
		} else {
			// If the Class Relationship exists, just update the comment and return the Class Relationship node
			setClassRelationshipCommentRepository.setClassRelationshipComment(tx, timeStamp, username, namespaceName, versioningType, startClassName, endClassName, label, comment);
			return result.map(nodes -> nodes[2]).orElseThrow(() -> new IllegalStateException("Incorrect Class Relationship result"));
		}
	}
}
