package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.util.Map;

public class UtilsLoginRepository extends BaseRepository {

	private static UtilsLoginRepository instance;
	
	private UtilsLoginRepository() {}
	
	public static synchronized UtilsLoginRepository getInstance() {
		if (instance == null) {
			instance = new UtilsLoginRepository();
		}
		return instance;
	}
	
	public String utilsLogin(Transaction tx, String username) {
		String matchQuery = """
			MATCH (u:User {username: $username}) RETURN u.password AS passwordHash
		""";
		try {
			Result result = tx.execute(matchQuery, Map.of("username", username));
			if (result.hasNext()) {
				return (String) result.next().get("passwordHash");
			}
		} catch (Exception e) {
			throwException("Error authenticating User: " + username, e);
		}
		return "";
	}
}
