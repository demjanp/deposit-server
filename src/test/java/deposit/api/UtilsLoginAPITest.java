package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.service.UtilsLoginService;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertEquals;

public class UtilsLoginAPITest extends BaseNeo4jTest {

	@Override
	protected Class<?>[] getProcedureClasses() {
		return new Class<?>[]{UtilsLoginAPI.class};
	}

	@Before
	public void setUp() {
		super.setUp();
		UtilsLoginService.getInstance().initialize(); // Ensure PasswordEncoder is initialized
	}


	@Test
	public void shouldLoginSuccessfully() {
		try (Session session = driver.session()) {
			// Setup: Create a User with known credentials
			String username = "testUser";
			String password = "password123";
			String hashedPassword = new BCryptPasswordEncoder().encode(password);
			session.writeTransaction(tx -> {
				tx.run("CREATE (u:User {username: $username, password: $hashedPassword})",
						Map.of("username", username, "hashedPassword", hashedPassword));
				return null; // Ensure to return a value to close the transaction
			});

			// Test: Login with the correct credentials
			String token = session.writeTransaction(tx -> {
				Result result = tx.run("CALL deposit.utils.login($username, $password) YIELD value AS token",
						Map.of("username", username, "password", password));
				// Extract token before committing the transaction
				String retrievedToken = result.single().get("token").asString();
				return retrievedToken; // Ensure the token is returned properly
			});

			// Verify: Token is not empty
			assertTrue(token != null && !token.isEmpty());
		}
	}

	@Test
	public void shouldFailLoginWithWrongPassword() {
		try (Session session = driver.session()) {
			// Setup: Create a User with known credentials
			String username = "testUser";
			String password = "password123";
			String hashedPassword = new BCryptPasswordEncoder().encode(password);
			session.writeTransaction(tx -> {
				tx.run("CREATE (u:User {username: $username, password: $hashedPassword})",
						Map.of("username", username, "hashedPassword", hashedPassword));
				tx.commit();
				return null;
			});

			// Test: Try to login with incorrect password
			String wrongPassword = "wrongPassword";
			try {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.utils.login($username, $password) YIELD value AS token",
							Map.of("username", username, "password", wrongPassword));
					tx.commit();
					return null;
				});
				fail("Expected RuntimeException not thrown");
			} catch (RuntimeException e) {
				assertTrue(e.getMessage().contains("Could not authenticate username: " + username));
			}
		}
	}

	@Test
	public void shouldFailLoginWithNonExistentUser() {
		try (Session session = driver.session()) {
			// Test: Try to login with a non-existent user
			String username = "nonExistentUser";
			String password = "password123";
			try {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.utils.login($username, $password) YIELD value AS token",
							Map.of("username", username, "password", password));
					tx.commit();
					return null;
				});
				fail("Expected RuntimeException not thrown");
			} catch (RuntimeException e) {
				assertTrue(e.getMessage().contains("Could not authenticate username: " + username));
			}
		}
	}
}
