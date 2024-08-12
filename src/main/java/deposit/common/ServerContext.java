package deposit.common;

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
	private static final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, String> activeTokens = new ConcurrentHashMap<>();

	private ServerContext() {}

	public static synchronized ServerContext getInstance() {
		if (instance == null) {
			instance = new ServerContext();
		}
		return instance;
	}

	// Method to clear all data for testing purposes
	public void clearAll() {
		store.clear();
		activeTokens.clear();
		secretKey = null;  // Ensure secret key is regenerated
	}

	// Authentication

	private static void generateSecretKey() {
		// Generate the secret key the first time it's needed
		if (secretKey == null) {
			byte[] keyBytes = new byte[KEY_LENGTH];
			new SecureRandom().nextBytes(keyBytes);
			secretKey = Keys.hmacShaKeyFor(keyBytes);
		}
	}

	public static void setDefaultUsername(String username) {
		defaultUsername = username;
	}

	public static String getDefaultUsername() {
		return defaultUsername;
	}

	public static String generateToken(String username) {
		generateSecretKey();
		String token = Jwts.builder()
				.subject(username)
				.signWith(secretKey)
				.compact();
		activeTokens.put(token, username);
		return token;
	}

	public static boolean validateToken(String token) {
		return (token == null) || token.isEmpty() || activeTokens.containsKey(token);
	}

	public static void deactivateToken(String token) {
		if (token != null) {
			String username = activeTokens.remove(token);
			if (username != null) {
				store.remove(username + ":namespace");
				store.remove(username + ":versioningType");
			}
		}
	}

	public static void deactivateUser(String username) {
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
		if (token == null || token.isEmpty()) {
			return defaultUsername;
		}
		String username = extractUsername(token);
		return username != null ? username : defaultUsername;
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
