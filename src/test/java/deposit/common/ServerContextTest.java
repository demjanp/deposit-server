package deposit.common;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ServerContextTest {

	@Test
	public void testSetAndGetActiveNamespace() {
		assertNull(ServerContext.getActiveNamespace());
		
		ServerContext.setActiveNamespace("testNamespace");
		assertEquals("testNamespace", ServerContext.getActiveNamespace());
	}
}
