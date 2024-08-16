package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;

import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

public class UserAPITest extends BaseNeo4jTest {

	private String token;

	@Override
	protected Class<?>[] getProcedureClasses() {
		return new Class<?>[]{CreateUserAPI.class, DeleteUserAPI.class};
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
	public void shouldCreateUserNodeWithoutToken() {
		try (Session session = driver.session()) {
			// Test: Create a User
			String username = "newUser2";
			String password = "password123";

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.user($username, $password)",
						Map.of("username", username, "password", password));
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
	
	@Test
	public void shouldDeleteUser() {
		try (Session session = driver.session()) {
			// Setup: Create a User
			String username = "userToDelete";
			String password = "password123";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.user($username, $password, $token)",
						Map.of("username", username, "password", password, "token", token));
				tx.commit();
				return null;
			});

			// Test: Delete the User
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.user($username, $token)",
						Map.of("username", username, "token", token));
				tx.commit();
				return null;
			});

			// Verify: User node was deleted
			Result result = session.run("MATCH (u:User {username: $username}) RETURN u", Map.of("username", username));
			assertFalse("User should be deleted and not found", result.hasNext());
		}
	}

	@Test
	public void shouldThrowExceptionWhenDeletingNonExistentUser() {
		try (Session session = driver.session()) {
			String nonExistentUsername = "nonExistentUser";

			// Test: Try to delete a non-existent user
			Exception exception = assertThrows(Exception.class, () -> {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.delete.user($username, $token)", 
							Map.of("username", nonExistentUsername, "token", token));
					tx.commit();
					return null;
				});
			});

			// Verify: Exception message
			String expectedMessage = "Error deleting the User node " + nonExistentUsername + ". It does not exist.";
			String actualMessage = exception.getMessage();
			assertTrue("Exception message should indicate that the user does not exist",
					actualMessage.contains(expectedMessage));
		}
	}

	@Test
	public void shouldThrowExceptionWhenDeletingActiveUser() {
		try (Session session = driver.session()) {
			String activeUsername = "testAdmin"; // This is the active user from setUp

			// Test: Try to delete the active user
			Exception exception = assertThrows(Exception.class, () -> {
				session.writeTransaction(tx -> {
					tx.run("CALL deposit.delete.user($username, $token)", 
							Map.of("username", activeUsername, "token", token));
					tx.commit();
					return null;
				});
			});

			// Verify: Exception message
			String expectedMessage = "Cannot delete the active User.";
			String actualMessage = exception.getMessage();
			assertTrue("Exception message should indicate that the active user cannot be deleted",
					actualMessage.contains(expectedMessage));
		}
	}
}
