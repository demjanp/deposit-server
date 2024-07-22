package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;
import java.util.Map;

public class CreateNamespaceRepository extends BaseRepository {

	private static CreateNamespaceRepository instance;

	private CreateNamespaceRepository() {}

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
				restored: NULL,
				createdAt: $timeStamp,
				createdBy: $username,
				updatedAt: $timeStamp,
				updatedBy: $username
			})
			CREATE (namespace)-[:CONTAINS]->(archive:Archive)
			WITH namespace, archive
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
			
			createNamespaceQuery += Versioning.getCreateArchiveCode(versioningType);
			createNamespaceQuery += "RETURN namespace";
			
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
		return null;
	}
}
