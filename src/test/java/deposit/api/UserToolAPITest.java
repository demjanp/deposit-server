package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

import java.util.UUID;

import java.util.Map;

import static org.junit.Assert.*;

public class UserToolAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}
	
	@Test
	public void shouldGetUserTool() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String userToolName = "TestUserTool";
			String data = "{\"key\":\"value\"}";
			String comment = "This is a test User Tool.";

			createNamespace(session, namespaceName, "transient", token);
			createUserTool(session, userToolName, data, comment, token);

			Result result = session.run("CALL deposit.get.userTool($userToolName, $token) YIELD node RETURN node",
					Map.of("userToolName", userToolName, "token", token));

			assertTrue("User Tool should be retrieved", result.hasNext());
			assertNotNull("Retrieved User Tool should not be null", result.next().get("node"));
		}
	}

	@Test
	public void shouldGetUserToolById() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String userToolName = "TestUserTool";
			String data = "{\"key\":\"value\"}";
			String comment = "This is a test User Tool.";

			createNamespace(session, namespaceName, "transient", token);
			String userToolId = createUserTool(session, userToolName, data, comment, token);

			Result result = session.run("CALL deposit.get.userToolById($userToolId, $token) YIELD node RETURN node",
					Map.of("userToolId", userToolId, "token", token));

			assertTrue("User Tool should be retrieved by ID", result.hasNext());
			assertNotNull("Retrieved User Tool by ID should not be null", result.next().get("node"));
		}
	}

	@Test
	public void shouldDeleteUserTool() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String userToolName1 = "TestUserTool1";
			String userToolName2 = "TestUserTool2";
			String data = "{\"key\":\"value\"}";
			String comment = "This is a test User Tool.";

			createNamespace(session, namespaceName, "transient", token);
			createUserTool(session, userToolName1, data, comment, token);
			createUserTool(session, userToolName2, data, comment, token);

			// Delete the User Tool
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.userTool($userToolName, $token)",
						Map.of("userToolName", userToolName1, "token", token));
				tx.commit();
				return null;
			});

			// Verify: User Tool should be deleted
			Result result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(ut:UserTool {name: $userToolName}) RETURN ut",
					Map.of("namespaceName", namespaceName, "userToolName", userToolName1));
			assertFalse("User Tool should be deleted and not found", result.hasNext());
		}
	}

	@Test
	public void shouldDeleteUserToolById() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String userToolName = "TestUserTool";
			String data = "{\"key\":\"value\"}";
			String comment = "This is a test User Tool.";

			createNamespace(session, namespaceName, "transient", token);
			String userToolId = createUserTool(session, userToolName, data, comment, token);

			// Delete the User Tool by ID
			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.userToolById($userToolId, $token)",
						Map.of("userToolId", userToolId, "token", token));
				tx.commit();
				return null;
			});

			// Verify: User Tool should be deleted by ID
			Result result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(ut:UserTool {id: $userToolId}) RETURN ut",
					Map.of("namespaceName", namespaceName, "userToolId", userToolId));
			assertFalse("User Tool should be deleted and not found by ID", result.hasNext());
		}
	}

	@Test
	public void shouldReturnEmptyWhenGettingNonExistentUserTool() {
		try (Session session = driver.session()) {
			String userToolName = "NonExistentUserTool";

			Result result = session.run("CALL deposit.get.userTool($userToolName, $token) YIELD node RETURN node",
					Map.of("userToolName", userToolName, "token", token));

			assertFalse("Result should be empty when retrieving a non-existent User Tool by name", result.hasNext());
		}
	}

	@Test
	public void shouldReturnEmptyWhenGettingNonExistentUserToolById() {
		try (Session session = driver.session()) {
			String userToolId = String.valueOf(UUID.randomUUID());

			Result result = session.run("CALL deposit.get.userToolById($userToolId, $token) YIELD node RETURN node",
					Map.of("userToolId", userToolId, "token", token));

			assertFalse("Result should be empty when retrieving a non-existent User Tool by ID", result.hasNext());
		}
	}

}
