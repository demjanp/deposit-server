package deposit.common;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SessionContextTest {

	@Before
	public void setUp() {
		// Ensure the ThreadLocal variables are cleared before each test
		SessionContext.setActiveNamespace(null);
		SessionContext.setActiveUsername(null);	 // This should clear the username to test the default value
	}

	@After
	public void tearDown() {
		// Clean up the ThreadLocal variables after each test
		SessionContext.setActiveNamespace(null);
		SessionContext.setActiveUsername(null);	 // This should reset the username to test the default value
	}

	@Test
	public void testSetAndGetActiveNamespace() {
		assertNull(SessionContext.getActiveNamespace());
		
		SessionContext.setActiveNamespace("testNamespace");
		assertEquals("testNamespace", SessionContext.getActiveNamespace());
	}

	@Test
	public void testSetAndGetActiveUsername() {
		assertEquals("neo4j", SessionContext.getActiveUsername());
		
		SessionContext.setActiveUsername("testUser");
		assertEquals("testUser", SessionContext.getActiveUsername());
	}

	@Test
	public void testSetActiveUsernameToNull() {
		SessionContext.setActiveUsername("testUser");
		assertEquals("testUser", SessionContext.getActiveUsername());
		
		SessionContext.setActiveUsername(null);
		assertEquals("neo4j", SessionContext.getActiveUsername());
	}

	@Test
	public void testInitialValueActiveUsername() {
		assertEquals("neo4j", SessionContext.getActiveUsername());
	}
}
