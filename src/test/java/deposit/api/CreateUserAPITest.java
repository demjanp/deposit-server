package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;

import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CreateUserAPITest extends BaseNeo4jTest {

	private String token;

	@Override
	protected Class<?>[] getProcedureClasses() {
		return new Class<?>[]{CreateUserAPI.class};
	}

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testAdmin");
	}

	@Test
	public void shouldCreateUserNode() {
		try (Session session = driver.session()) {
			// Test: Create a User
			String username = "newUser";
			String password = "password123";

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.user($username, $password, $token)",
						Map.of("username", username, "password", password, "token", token));
				tx.commit();
				return null;
			});

			// Verify: User node was created
			Result result = session.run("MATCH (u:User {username: $username}) RETURN u", Map.of("username", username));
			assertTrue(result.hasNext());
		}
	}

	@Test
	public void shouldThrowExceptionIfUserAlreadyExists() {
		try (Session session = driver.session()) {
			// Setup: Create a User
			String username = "existingUser";
			String password = "password123";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.user($username, $password, $token)",
						Map.of("username", username, "password", password, "token", token));
				tx.commit();
				return null;
			});

			// Test: Try to create the same User again, which should throw an exception
			try {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.create.user($username, $password, $token)",
							Map.of("username", username, "password", password, "token", token));
					tx.commit();
					return null;
				});
				fail("Expected RuntimeException not thrown");
			} catch (RuntimeException e) {
				assertTrue(e.getMessage().contains("Error creating User. A User with the name: " + username + " already exists."));
			}
		}
	}
}
