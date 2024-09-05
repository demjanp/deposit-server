package deposit.service;

import deposit.repository.GetObjectClassRepository;
import deposit.repository.GetClassRelationshipRepository;
import deposit.repository.CreateClassRelationshipRepository;
import deposit.repository.CreateRelationshipRepository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;

import java.time.ZonedDateTime;
import java.util.stream.Stream;

public class EnsureRelationshipService extends BaseService {

	private static EnsureRelationshipService instance;
	private final GetObjectClassRepository getObjectClassRepository;
	private final GetClassRelationshipRepository getClassRelationshipRepository;
	private final CreateClassRelationshipRepository createClassRelationshipRepository;
	private final CreateRelationshipRepository createRelationshipRepository;

	private EnsureRelationshipService() {
		this.getObjectClassRepository = GetObjectClassRepository.getInstance();
		this.getClassRelationshipRepository = GetClassRelationshipRepository.getInstance();
		this.createClassRelationshipRepository = CreateClassRelationshipRepository.getInstance();
		this.createRelationshipRepository = CreateRelationshipRepository.getInstance();
	}

	public static synchronized EnsureRelationshipService getInstance() {
		if (instance == null) {
			instance = new EnsureRelationshipService();
		}
		return instance;
	}

	public Relationship ensureRelationship(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String startObjectId, String endObjectId, String label, Double weight) {
		Node startClassNode = getObjectClassRepository.getObjectClass(tx, namespaceName, startObjectId);
		String startClassName = String.valueOf(startClassNode.getProperty("name"));
		Node endClassNode = getObjectClassRepository.getObjectClass(tx, namespaceName, endObjectId);
		String endClassName = String.valueOf(endClassNode.getProperty("name"));
		if (!startClassName.equals(endClassName)) {
			// Ensure a Class Relationship between the start and end Classes
			Stream<Relationship> result = getClassRelationshipRepository.getClassRelationship(tx, namespaceName, startClassName, endClassName, label);
			if (result.findAny().isEmpty()) {
				createClassRelationshipRepository.createClassRelationship(tx, timeStamp, username, namespaceName, versioningType, startClassName, endClassName, label, "");
			}
		}
		
		return createRelationshipRepository.createRelationship(tx, timeStamp, username, namespaceName, versioningType, startObjectId, endObjectId, label, weight);
	}
}
