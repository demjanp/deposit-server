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
import static org.junit.Assert.assertEquals;

public class UserAPITest extends BaseNeo4jTest {

	private String token;

	@Override
	protected Class<?>[] getProcedureClasses() {
		return new Class<?>[]{CreateUserAPI.class, DeleteUserAPI.class, GetUserAPI.class, GetUserByIdAPI.class};
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

	@Test
	public void shouldGetUserByUsername() {
		try (Session session = driver.session()) {
			// Setup: Create a User
			String username = "getUserTest";
			String password = "password123";
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.user($username, $password, $token)",
						Map.of("username", username, "password", password, "token", token));
				tx.commit();
				return null;
			});

			// Test: Retrieve the User by username
			Result result = session.run("CALL deposit.get.user($username, $token) YIELD node RETURN node AS user",
					Map.of("username", username, "token", token));

			// Verify: User node was retrieved
			assertTrue("User should be retrieved by username", result.hasNext());
			assertEquals(username, result.single().get("user").get("username").asString());
		}
	}

	@Test
	public void shouldGetUserById() {
		try (Session session = driver.session()) {
			// Setup: Create a User
			String username = "getUserByIdTest";
			String password = "password123";
			String userId = session.writeTransaction(tx -> {
				Result result = tx.run("CALL deposit.create.user($username, $password, $token) YIELD node RETURN node.id AS userId",
						Map.of("username", username, "password", password, "token", token));
				return result.single().get("userId").asString();
			});

			// Test: Retrieve the User by userId
			Result result = session.run("CALL deposit.get.userById($userId, $token) YIELD node RETURN node AS user",
					Map.of("userId", userId, "token", token));

			// Verify: User node was retrieved
			assertTrue("User should be retrieved by userId", result.hasNext());
			assertEquals(username, result.single().get("user").get("username").asString());
		}
	}

	@Test
	public void shouldGetAllUsersWhenNoUsernameSpecified() {
		try (Session session = driver.session()) {
			// Setup: Create two Users
			String username1 = "userOne";
			String password1 = "password123";
			String username2 = "userTwo";
			String password2 = "password123";

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.create.user($username, $password, $token)",
						Map.of("username", username1, "password", password1, "token", token));
				tx.run("CALL deposit.create.user($username, $password, $token)",
						Map.of("username", username2, "password", password2, "token", token));
				tx.commit();
				return null;
			});

			// Test: Retrieve all users
			Result result = session.run("CALL deposit.get.user('', $token) YIELD node RETURN node AS user",
					Map.of("token", token));

			// Verify: All users are retrieved
			assertTrue("At least two users should be retrieved", result.list().size() >= 2);
		}
	}

	@Test
	public void shouldReturnEmptyForInvalidUserId() {
		try (Session session = driver.session()) {
			String invalidUserId = "nonExistentUserId";

			// Test: Try to retrieve a user with an invalid userId
			Result result = session.run("CALL deposit.get.userById($userId, $token) YIELD node RETURN node AS node",
					Map.of("userId", invalidUserId, "token", token));

			// Verify: No user should be retrieved
			assertFalse("No user should be retrieved with an invalid userId", result.hasNext());
		}
	}
}
