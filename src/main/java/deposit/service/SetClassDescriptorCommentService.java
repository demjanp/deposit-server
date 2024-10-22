package deposit.service;

import deposit.repository.SetClassDescriptorCommentRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;

public class SetClassDescriptorCommentService extends BaseService {

	private static SetClassDescriptorCommentService instance;
	private final SetClassDescriptorCommentRepository setClassDescriptorCommentRepository;

	private SetClassDescriptorCommentService() {
		this.setClassDescriptorCommentRepository = SetClassDescriptorCommentRepository.getInstance();
	}

	public static synchronized SetClassDescriptorCommentService getInstance() {
		if (instance == null) {
			instance = new SetClassDescriptorCommentService();
		}
		return instance;
	}

	public void setClassDescriptorComment(Transaction tx, String className, String descriptorName, String comment, String token) {
		
		validateToken(token);
		validateName(className);
		validateName(descriptorName);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot set Class Descriptor comment without an active Namespace.");
		}
		
		setClassDescriptorCommentRepository.setClassDescriptorComment(tx, timeStamp, username, namespaceName, versioningType, className, descriptorName, comment);
	}
}
