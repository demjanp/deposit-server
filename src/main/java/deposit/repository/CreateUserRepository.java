package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class CreateUserRepository extends BaseRepository {

	private static CreateUserRepository instance;
	
	private CreateUserRepository() {}
	
	public static synchronized CreateUserRepository getInstance() {
		if (instance == null) {
			instance = new CreateUserRepository();
		}
		return instance;
	}
	
	public Node createUser(Transaction tx, ZonedDateTime timeStamp, String currentUsername, String newUsername, String hashedPassword) {
		String validateNameQuery = """
			MATCH (node:User {username: $newUsername}) RETURN node
		""";
		try {
			Result validation = tx.execute(validateNameQuery, Map.of("newUsername", newUsername));
			if (validation.hasNext()) {
				throwException("Error creating User. A User with the name: " + newUsername + " already exists.");
			}
		} catch (Exception e) {
			throwException("Error creating User with username: " + newUsername, e);
		}
		
		String createQuery = """
			CREATE (node:User {
				id: randomUUID(),
				username: $newUsername,
				password: $hashedPassword,
				createdAt: $timeStamp,
				createdBy: $currentUsername,
				updatedAt: $timeStamp,
				updatedBy: $currentUsername
			})
			RETURN node
		""";

		Map<String, Object> params = Map.of(
			"timeStamp", timeStamp,
			"currentUsername", currentUsername,
			"newUsername", newUsername,
			"hashedPassword", hashedPassword
		);

		try {
			Result result = tx.execute(createQuery, params);
			if (result.hasNext()) {
				return (Node) result.next().get("node");
			} else {
				throwException("User creation failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error creating User with usernamename: " + newUsername, e);
		}
		return null;
	}
}
