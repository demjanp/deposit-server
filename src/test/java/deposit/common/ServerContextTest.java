package deposit.common;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class ServerContextTest {

	private ServerContext serverContext;

	@Before
	public void setUp() {
		serverContext = ServerContext.getInstance();
		serverContext.clearAll();  // Clear the state before each test
	}

	@Test
	public void testSetAndGetActiveNamespace() {
		String token = serverContext.generateToken("testUser");

		assertEquals("", ServerContext.getActiveNamespace(token));

		ServerContext.setActiveNamespace("testNamespace", token);
		assertEquals("testNamespace", ServerContext.getActiveNamespace(token));
	}

	@Test
	public void testTokenGenerationAndValidation() {
		String token = serverContext.generateToken("testUser");
		assertNotNull(token);
		assertTrue(ServerContext.validateToken(token));

		ServerContext.deactivateToken(token);
		assertFalse(ServerContext.validateToken(token));
	}

	@Test
	public void testExtractUsername() {
		String token = serverContext.generateToken("testUser");
		String username = ServerContext.extractUsername(token);
		assertEquals("testUser", username);
	}

	@Test
	public void testDeactivateUser() {
		String token1 = serverContext.generateToken("testUser1");
		String token2 = serverContext.generateToken("testUser2");
		assertTrue(ServerContext.validateToken(token1));
		assertTrue(ServerContext.validateToken(token2));

		serverContext.deactivateUser("testUser1");
		assertFalse(ServerContext.validateToken(token1));
		assertTrue(ServerContext.validateToken(token2));
	}

	@Test
	public void testSetAndGetVersioningType() {
		String token = serverContext.generateToken("testUser");
		assertEquals("", ServerContext.getVersioningType(token));
		ServerContext.setVersioningType("type1", token);
		assertEquals("type1", ServerContext.getVersioningType(token));
	}

	@Test
	public void testGetActiveUsernameWithToken() {
		String token = serverContext.generateToken("testUser");
		assertEquals("testUser", ServerContext.getActiveUsername(token));
	}

	@Test
	public void testGetActiveUsernameWithoutToken() {
		assertEquals("neo4j", ServerContext.getActiveUsername(""));
	}

	@Test
	public void testSetAndGetActiveNamespaceWithoutToken() {
		String token = "";  // No token provided
		ServerContext.setActiveNamespace("defaultNamespace", token);
		assertEquals("defaultNamespace", ServerContext.getActiveNamespace(token));
	}

	@Test
	public void testSetAndGetVersioningTypeWithoutToken() {
		String token = "";  // No token provided
		ServerContext.setVersioningType("defaultType", token);
		assertEquals("defaultType", ServerContext.getVersioningType(token));
	}
}
