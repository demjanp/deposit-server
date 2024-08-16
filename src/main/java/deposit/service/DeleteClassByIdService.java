package deposit.service;

import deposit.repository.GetClassByIdRepository;

import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;

public class DeleteClassByIdService extends BaseService {

	private static DeleteClassByIdService instance;
	private final GetClassByIdRepository getClassByIdRepository;
	private final EnsureClassDeletionService ensureClassDeletionService;

	private DeleteClassByIdService() {
		this.getClassByIdRepository = GetClassByIdRepository.getInstance();
		this.ensureClassDeletionService = EnsureClassDeletionService.getInstance();
	}

	public static synchronized DeleteClassByIdService getInstance() {
		if (instance == null) {
			instance = new DeleteClassByIdService();
		}
		return instance;
	}

	public void deleteClassById(Transaction tx, String classId, String token) {
		
		validateToken(token);
		validateId(classId);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot delete a Class without an active Namespace.");
		}

		// Get the Class name
		Node classNode = getClassByIdRepository.getClassById(tx, namespaceName, classId);
		if (classNode == null) {
			throwException("Error deleting the Class " + classId + ". It does not exist.");
		}
		String className = (String) classNode.getProperty("name");
		
		// Delete the Class node
		ensureClassDeletionService.ensureClassDeletion(tx, timeStamp, username, namespaceName, versioningType, className);
	}
}
