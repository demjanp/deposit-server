package deposit.service;

import deposit.repository.SetClassCommentRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;

public class SetClassCommentService extends BaseService {

	private static SetClassCommentService instance;
	private final SetClassCommentRepository setClassCommentRepository;

	private SetClassCommentService() {
		this.setClassCommentRepository = SetClassCommentRepository.getInstance();
	}

	public static synchronized SetClassCommentService getInstance() {
		if (instance == null) {
			instance = new SetClassCommentService();
		}
		return instance;
	}

	public void setClassComment(Transaction tx, String className, String comment, String token) {
		
		validateToken(token);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot set Class comment without an active Namespace.");
		}
		
		setClassCommentRepository.setClassComment(tx, timeStamp, username, namespaceName, versioningType, className, comment);
	}
}
