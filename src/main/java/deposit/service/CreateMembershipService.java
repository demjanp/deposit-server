package deposit.service;

import deposit.repository.CreateMembershipRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Relationship;

import java.time.ZonedDateTime;

public class CreateMembershipService extends BaseService {
	
	private static CreateMembershipService instance;
	private final CreateMembershipRepository createMembershipRepository;
	private final EnsureClassService ensureClassService;
	
	private CreateMembershipService() {
		this.ensureClassService = EnsureClassService.getInstance();
		this.createMembershipRepository = CreateMembershipRepository.getInstance();
	}
	
	public static synchronized CreateMembershipService getInstance() {
		if (instance == null) {
			instance = new CreateMembershipService();
		}
		return instance;
	}
	
	public Relationship createMembership(Transaction tx, String className, String objectId, String token) {
		
		validateToken(token);
		validateName(className);
		validateId(objectId);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot create Class Descriptor without an active Namespace.");
		}
		
		ensureClassService.ensureClass(tx, timeStamp, username, namespaceName, versioningType, className, token);
		return createMembershipRepository.createMembership(tx, timeStamp, username, namespaceName, versioningType, className, objectId);
	}
}
