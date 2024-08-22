package deposit.service;

import deposit.repository.SetClassRelationshipCommentRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;

public class SetClassRelationshipCommentService extends BaseService {

	private static SetClassRelationshipCommentService instance;
	private final SetClassRelationshipCommentRepository setClassRelationshipCommentRepository;

	private SetClassRelationshipCommentService() {
		this.setClassRelationshipCommentRepository = SetClassRelationshipCommentRepository.getInstance();
	}

	public static synchronized SetClassRelationshipCommentService getInstance() {
		if (instance == null) {
			instance = new SetClassRelationshipCommentService();
		}
		return instance;
	}

	public void setClassRelationshipComment(Transaction tx, String startClassName, String endClassName, String label, String comment, String token) {
		
		if (startClassName.equals(endClassName)) {
			throwException("Cannot have a Class Relationship within the same Class.");
		}
		validateToken(token);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot update a Class Relationship without an active Namespace.");
		}
		
		setClassRelationshipCommentRepository.setClassRelationshipComment(tx, timeStamp, username, namespaceName, versioningType, startClassName, endClassName, label, comment);
	}
}
