package deposit.common;

import org.neo4j.graphdb.GraphDatabaseService;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;


public class ServerContext {

	private static final int KEY_LENGTH = 32; // 256 bits
	private static volatile String defaultUsername = "neo4j";

	private static ServerContext instance;

	private static SecretKey secretKey;
	private static GraphDatabaseService graphDb;
	private static final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, String> activeTokens = new ConcurrentHashMap<>();

	private ServerContext() {}

	public static synchronized ServerContext getInstance() {
		if (instance == null) {
			instance = new ServerContext();
		}
		return instance;
	}

	public synchronized void initialize(GraphDatabaseService graphDatabaseService) {
		if (this.graphDb == null) {
			this.graphDb = graphDatabaseService;
		} else {
			throw new IllegalStateException("GraphDatabaseService is already initialized");
		}
	}


	// Authentication

	private static void generateSecretKey() {
		// Generate the secret key the first time it's needed
		if (secretKey == null) {
			byte[] keyBytes = new byte[32];
			new SecureRandom().nextBytes(keyBytes);
			secretKey = Keys.hmacShaKeyFor(keyBytes);
		}
	}

	private static void setDefaultUsername(String username) {
		defaultUsername = username;
	}

	private static String getDefaultUsername() {
		return defaultUsername;
	}

	public String generateToken(String username) {
		generateSecretKey();
		String token = Jwts.builder()
				.subject(username)
				.signWith(secretKey)
				.compact();
		activeTokens.put(token, username);
		return token;
	}

	public static boolean validateToken(String token) {
		if (!token.isEmpty()) {
			return activeTokens.containsKey(token);
		}
		return true;
	}

	public static void deactivateToken(String token) {
		activeTokens.remove(token);
	}

	public void deactivateUser(String username) {
		activeTokens.entrySet().removeIf(entry -> entry.getValue().equals(username));
	}

	public static String extractUsername(String token) {
		generateSecretKey();
		try {
			Claims claims = Jwts.parser()
				.verifyWith(secretKey)
				.build()
				.parseSignedClaims(token)
				.getPayload();
			return claims.getSubject();
		} catch (Exception e) {
			ExceptionHandler.throwException("Error extracting username from token", e);
			return null;
		}
	}

	public static String getActiveUsername(String token) {
		if (token.isEmpty()) {
			return defaultUsername;
		}
		return extractUsername(token);
	}


	// Namespace

	public static void setActiveNamespace(String namespace, String token) {
		String username = getActiveUsername(token);
		store.put(username + ":namespace", namespace);
	}

	public static String getActiveNamespace(String token) {
		String username = getActiveUsername(token);
		return store.getOrDefault(username + ":namespace", "");
	}


	// Versioning

	public static void setVersioningType(String value, String token) {
		String username = getActiveUsername(token);
		store.put(username + ":versioningType", value);
	}

	public static String getVersioningType(String token) {
		String username = getActiveUsername(token);
		return store.getOrDefault(username + ":versioningType", "");
	}
}
