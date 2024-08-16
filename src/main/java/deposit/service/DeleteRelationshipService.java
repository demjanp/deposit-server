package deposit.service;

import deposit.repository.GetRelationshipRepository;
import deposit.repository.DeleteRelationshipByIdRepository;
import deposit.common.ServerContext;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.Optional;
import java.util.stream.Stream;
import java.time.ZonedDateTime;

public class DeleteRelationshipService extends BaseService {

	private static DeleteRelationshipService instance;
	private final GetRelationshipRepository getRelationshipRepository;
	private final DeleteRelationshipByIdRepository deleteRelationshipByIdRepository;

	private DeleteRelationshipService() {
		this.deleteRelationshipByIdRepository = DeleteRelationshipByIdRepository.getInstance();
		this.getRelationshipRepository = GetRelationshipRepository.getInstance();
	}

	public static synchronized DeleteRelationshipService getInstance() {
		if (instance == null) {
			instance = new DeleteRelationshipService();
		}
		return instance;
	}

	public void deleteRelationship(Transaction tx, String startObjectId, String endObjectId, String label, String token) {
		
		if (startObjectId.equals(endObjectId)) {
			throwException("Cannot delete a Relationship of an Object with itself.");
		}
		validateToken(token);
		validateId(startObjectId);
		validateId(endObjectId);
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String username = ServerContext.getActiveUsername(token);
		String namespaceName = ServerContext.getActiveNamespace(token);
		String versioningType = ServerContext.getVersioningType(token);
		if (namespaceName == null) {
			throwException("Cannot delete a Relationship without an active Namespace.");
		}
		
		// Get Relationship Id
		Stream<Node[]> relationships = getRelationshipRepository.getRelationship(tx, namespaceName, startObjectId, endObjectId, label);
		Optional<Node[]> optionalNodes = relationships.findFirst();

		Node[] nodes = optionalNodes.orElseThrow(() ->
			new RuntimeException("Error deleting Relationship " + label + ". It does not exist.")
		);

		String relationshipId = (String) nodes[2].getProperty("id");
		
		deleteRelationshipByIdRepository.deleteRelationshipById(tx, timeStamp, username, namespaceName, versioningType, relationshipId);
	}
}
