package deposit.service;

import deposit.repository.GetObjectClassRepository;
import deposit.repository.DeleteMembershipRepository;
import deposit.repository.SetMembershipRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Relationship;

import java.time.ZonedDateTime;

public class SetMembershipService extends BaseService {
	
	private static SetMembershipService instance;
	private final GetObjectClassRepository getObjectClassRepository;
	private final DeleteMembershipRepository deleteMembershipRepository;
	private final SetMembershipRepository setMembershipRepository;
	private final EnsureClassService ensureClassService;
	
	private SetMembershipService() {
		this.ensureClassService = EnsureClassService.getInstance();
		this.getObjectClassRepository = GetObjectClassRepository.getInstance();
		this.deleteMembershipRepository = DeleteMembershipRepository.getInstance();
		this.setMembershipRepository = SetMembershipRepository.getInstance();
	}
	
	public static synchronized SetMembershipService getInstance() {
		if (instance == null) {
			instance = new SetMembershipService();
		}
		return instance;
	}
	
	public Relationship setMembership(Transaction tx, String className, String objectId, String token) {
		
		validateToken(token);
		validateName(className);
		validateId(objectId);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot set Class Membership without an active Namespace.");
		}
		
		Node classNode = getObjectClassRepository.getObjectClass(tx, namespaceName, objectId);
		String existingClassName = String.valueOf(classNode.getProperty("name"));
		if (!existingClassName.isEmpty()) {
			deleteMembershipRepository.deleteMembership(tx, timeStamp, username, namespaceName, versioningType, existingClassName, objectId);
		}
		ensureClassService.ensureClass(tx, timeStamp, username, namespaceName, versioningType, className);
		return setMembershipRepository.setMembership(tx, timeStamp, username, namespaceName, versioningType, className, objectId);
	}
}
