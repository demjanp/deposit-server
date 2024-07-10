package deposit.core;

public class SessionContext {
	private static final ThreadLocal<String> activeDatabase = new ThreadLocal<>();

	public static void setActiveDatabase(String database) {
		activeDatabase.set(database);
	}

	public static String getActiveDatabase() {
		return activeDatabase.get();
	}
}
