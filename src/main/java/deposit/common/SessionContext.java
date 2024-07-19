package deposit.common;

public class SessionContext {
	private static final String DEFAULT_USERNAME = "neo4j";
	private static final ThreadLocal<String> activeNamespace = new ThreadLocal<>();
	private static final ThreadLocal<String> activeUsername = new ThreadLocal<>();

	public static void setActiveNamespace(String namespace) {
		activeNamespace.set(namespace);
	}

	public static String getActiveNamespace() {
		return activeNamespace.get();
	}

	public static void setActiveUsername(String username) {
		activeUsername.set(username);
	}

	public static String getActiveUsername() {
		String username = activeUsername.get();
		if (username == null) {
			return DEFAULT_USERNAME;
		} else {
			return activeUsername.get();
		}
	}
}
