package deposit.service;

import deposit.repository.DeleteMembershipRepository;
import deposit.repository.GetObjectClassesRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;
import java.util.stream.Stream;

public class DeleteMembershipService extends BaseService {

	private static DeleteMembershipService instance;
	private final DeleteMembershipRepository deleteMembershipRepository;
	private final GetObjectClassesRepository getObjectClassesRepository;

	private DeleteMembershipService() {
		this.deleteMembershipRepository = DeleteMembershipRepository.getInstance();
		this.getObjectClassesRepository = GetObjectClassesRepository.getInstance();
	}

	public static synchronized DeleteMembershipService getInstance() {
		if (instance == null) {
			instance = new DeleteMembershipService();
		}
		return instance;
	}

	public void deleteMembership(Transaction tx, String className, String objectId, String token) {
		
		validateToken(token);
		validateId(objectId);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot delete a Membership without an active Namespace.");
		}
		
		// Don't delete a Membership if the Object only has one class
		Stream<Node> classes = getObjectClassesRepository.getObjectClasses(tx, namespaceName, objectId);
		if (classes.count() < 2) {
			throwException("Cannot delete a Membership of the only Class of an Object.");
		}
		
		deleteMembershipRepository.deleteMembership(tx, timeStamp, username, namespaceName, versioningType, className, objectId);
	}
}
