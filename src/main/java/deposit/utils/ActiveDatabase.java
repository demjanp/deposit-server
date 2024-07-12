package deposit.utils;

import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.GraphDatabaseService;

public class ActiveDatabase {
	private static final ThreadLocal<String> activeDatabase = new ThreadLocal<>();
	private static DatabaseManagementService dbManagementService;

	public static String get() {
		return activeDatabase.get();
	}

	public static void set(String databaseName) {
		activeDatabase.set(databaseName);
	}

	public static void setService(DatabaseManagementService managementService) {
		dbManagementService = managementService;
	}

	public static GraphDatabaseService getService() {
		String dbName = get();
		if (dbName == null || dbName.isEmpty()) {
			throw new RuntimeException("No active database set. Use set(databaseName) to set the active database.");
		}
		return dbManagementService.database(dbName);
	}
}
