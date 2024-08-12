package deposit.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerContextTest {

	private ServerContext serverContext;

	@BeforeEach
	void setUp() {
		serverContext = ServerContext.getInstance();
		serverContext.clearAll();  // Clear all data before each test
	}

	@Test
	void testSingletonInstance() {
		ServerContext instance1 = ServerContext.getInstance();
		ServerContext instance2 = ServerContext.getInstance();
		assertSame(instance1, instance2, "ServerContext should return the same instance");
	}

	@Test
	void testDefaultUsername() {
		assertEquals("neo4j", ServerContext.getDefaultUsername(), "Default username should be 'neo4j'");

		ServerContext.setDefaultUsername("testUser");
		assertEquals("testUser", ServerContext.getDefaultUsername(), "Default username should be 'testUser'");
	}

	@Test
	void testGenerateAndValidateToken() {
		String username = "testUser";
		String token = ServerContext.generateToken(username);

		assertNotNull(token, "Token should not be null");
		assertTrue(ServerContext.validateToken(token), "Token should be valid");

		ServerContext.deactivateToken(token);
		assertFalse(ServerContext.validateToken(token), "Token should no longer be valid after deactivation");
	}

	@Test
	void testExtractUsernameFromToken() {
		String username = "testUser";
		String token = ServerContext.generateToken(username);

		String extractedUsername = ServerContext.extractUsername(token);
		assertEquals(username, extractedUsername, "Extracted username should match the original username");
	}

	@Test
	void testGetActiveUsername() {
		String defaultUsername = ServerContext.getDefaultUsername();
		assertEquals(defaultUsername, ServerContext.getActiveUsername(null), "Active username should default to the default username when token is null");
		assertEquals(defaultUsername, ServerContext.getActiveUsername(""), "Active username should default to the default username when token is empty");

		String username = "testUser";
		String token = ServerContext.generateToken(username);
		assertEquals(username, ServerContext.getActiveUsername(token), "Active username should match the username from the token");
	}

	@Test
	void testNamespaceManagement() {
		String username = "testUser";
		String namespace = "TestNamespace";
		String token = ServerContext.generateToken(username);

		ServerContext.setActiveNamespace(namespace, token);
		assertEquals(namespace, ServerContext.getActiveNamespace(token), "Active namespace should be correctly set and retrieved");

		ServerContext.deactivateToken(token);
		assertEquals("", ServerContext.getActiveNamespace(token), "After deactivating the token, active namespace should be an empty string");
	}

	@Test
	void testVersioningTypeManagement() {
		String username = "testUser";
		String versioningType = "transient";
		String token = ServerContext.generateToken(username);

		ServerContext.setVersioningType(versioningType, token);
		assertEquals(versioningType, ServerContext.getVersioningType(token), "Versioning type should be correctly set and retrieved");

		ServerContext.deactivateToken(token);
		// Now, after deactivation, the versioning type should be an empty string
		assertEquals("", ServerContext.getVersioningType(token), "After deactivating the token, versioning type should be an empty string");
	}

	@Test
	void testDeactivateUser() {
		String username1 = "user1";
		String username2 = "user2";

		String token1 = ServerContext.generateToken(username1);
		String token2 = ServerContext.generateToken(username2);

		ServerContext.deactivateUser(username1);

		assertFalse(ServerContext.validateToken(token1), "Token for deactivated user should be invalid");
		assertTrue(ServerContext.validateToken(token2), "Token for non-deactivated user should remain valid");
	}
}
