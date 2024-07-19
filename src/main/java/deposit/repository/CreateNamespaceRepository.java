package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.time.ZonedDateTime;
import java.util.Map;

public class CreateNamespaceRepository {

	private static CreateNamespaceRepository instance;
	private static final Logger logger = Logger.getLogger(CreateNamespaceRepository.class.getName());

	private CreateNamespaceRepository() {
		// Private constructor to prevent instantiation
	}

	public static synchronized CreateNamespaceRepository getInstance() {
		if (instance == null) {
			instance = new CreateNamespaceRepository();
		}
		return instance;
	}

	public Node createNamespace(Transaction tx, ZonedDateTime timeStamp, String depositVersion, String username, String name, String description, String versioningType) {
		String validateNameQuery = """
			MATCH (namespace:Namespace {name: $name}) RETURN namespace
		""";

		String createNamespaceQuery = """
			CREATE (namespace:Namespace {
				id: randomUUID(),
				name: $name,
				description: $description,
				depositVersion: $depositVersion,
				versioningType: $versioningType,
				restored: null,
				createdAt: $timeStamp,
				createdBy: $username,
				updatedAt: $timeStamp,
				updatedBy: $username
			})
			RETURN namespace
		""";

		Map<String, Object> params = Map.of(
			"timeStamp", timeStamp,
			"depositVersion", depositVersion,
			"username", username,
			"name", name,
			"description", description,
			"versioningType", versioningType
		);

		try {
			// Execute validation query
			Result validation = tx.execute(validateNameQuery, Map.of("name", name));
			if (validation.hasNext()) {
				throw new RuntimeException("Error creating Namespace. A namespace with the name: " + name + " already exists.");
			}

			// Execute namespace creation
			Result result = tx.execute(createNamespaceQuery, params);

			if (result.hasNext()) {
				Node node = (Node) result.next().get("namespace");
				return node;
			} else {
				logger.log(Level.SEVERE, "Namespace creation failed: No result returned");
				throw new RuntimeException("Namespace creation failed.");
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error creating Namespace with name: " + name, e);
			throw new RuntimeException("Error creating Namespace", e);
		}
	}
}
