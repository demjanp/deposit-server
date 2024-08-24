package deposit.repository;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;
import java.util.Map;

public class SetPasswordRepository extends BaseRepository {

	private static SetPasswordRepository instance;
	
	private SetPasswordRepository() {}
	
	public static synchronized SetPasswordRepository getInstance() {
		if (instance == null) {
			instance = new SetPasswordRepository();
		}
		return instance;
	}
	
	public void setPassword(Transaction tx, ZonedDateTime timeStamp, String currentUsername, String updatedUsername, String hashedPassword) {
		String updateQuery = """
				MATCH (updated:User {username: $updatedUsername})
				SET updated.password = $hashedPassword,
					updated.updatedAt = $timeStamp,
					updated.updatedBy = $currentUsername
				RETURN updated
			""";
		Map<String, Object> params = Map.of(
			"timeStamp", timeStamp,
			"currentUsername", currentUsername,
			"updatedUsername", updatedUsername,
			"hashedPassword", hashedPassword
		);
		try {
			tx.execute(updateQuery, params);
		} catch (Exception e) {
			throwException("Error updating User " + updatedUsername, e);
		}
	}
}
