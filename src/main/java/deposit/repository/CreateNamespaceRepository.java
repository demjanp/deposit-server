package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class CreateNamespaceRepository extends BaseRepository<CreateNamespaceRepository> {

	CreateNamespaceRepository() { // Package-private constructor to allow access by BaseRepository
		// Prevent direct instantiation
	}

	public static CreateNamespaceRepository getInstance() {
		return (CreateNamespaceRepository) BaseRepository.getInstance(CreateNamespaceRepository.class);
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
				throwException("Error creating Namespace. A namespace with the name: " + name + " already exists.");
			}

			// Execute namespace creation
			Result result = tx.execute(createNamespaceQuery, params);

			if (result.hasNext()) {
				Node node = (Node) result.next().get("namespace");
				return node;
			} else {
				throwException("Namespace creation failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error creating Namespace with name: " + name, e);
		}
		return null; // This line is unreachable but needed to satisfy return type
	}
}
