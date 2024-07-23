package deposit.common;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

public class ServerContext {
	
	private static volatile String activeNamespace = null;
	private static volatile String versioningType = null;
	// TODO store a lookup table to get username, activeNamespace and versioningType based on token

	public static void setActiveNamespace(String namespace) {
		// TODO implement token based user management
		activeNamespace = namespace;
	}

	public static String getActiveNamespace() {
		// TODO implement token based user management
		return activeNamespace;
	}
	
	public static void setActiveUsername(String username) {
		// TODO implement token based user management
	}

	public static String getActiveUsername(Transaction tx) {
		// TODO implement token based user management
		String username = "";
		try (Result result = tx.execute("CALL dbms.showCurrentUser() YIELD username")) {
			if (result.hasNext()) {
				username = (String) result.next().get("username");
			}
		}
		return username;
	}
	
	public static void setVersioningType(String value) {
		// TODO implement token based user management
		versioningType = value;
	}

	public static String getVersioningType() {
		// TODO implement token based user management
		return versioningType;
	}
}
