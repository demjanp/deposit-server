package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

import java.io.IOException;
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

	private void createNamespace(Session session, String namespaceName) {
		session.writeTransaction(tx -> {
			tx.run("CALL deposit.create.namespace($namespaceName, '', 'transient', $token)", Map.of("namespaceName", namespaceName, "token", token));
			tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)", Map.of("namespaceName", namespaceName, "token", token));
			tx.commit();
			return null;
		});
	}

	private String createUserTool(Session session, String namespaceName, String userToolName, String data, String comment) {
		session.writeTransaction(tx -> {
			tx.run("CALL deposit.create.userTool($userToolName, $data, $comment, $token) YIELD node RETURN node.id AS userToolId",
					Map.of("userToolName", userToolName, "data", data, "comment", comment, "token", token));
			tx.commit();
			return null;
		});

		Result result = session.run("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(ut:UserTool {name: $userToolName}) RETURN ut.id AS userToolId",
				Map.of("namespaceName", namespaceName, "userToolName", userToolName));

		assertTrue("User Tool node should exist with the correct name", result.hasNext());
		return result.next().get("userToolId").asString();
	}

	@Test
	public void shouldGetUserTool() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String userToolName = "TestUserTool";
			String data = "{\"key\":\"value\"}";
			String comment = "This is a test User Tool.";

			createNamespace(session, namespaceName);
			createUserTool(session, namespaceName, userToolName, data, comment);

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

			createNamespace(session, namespaceName);
			String userToolId = createUserTool(session, namespaceName, userToolName, data, comment);

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

			createNamespace(session, namespaceName);
			createUserTool(session, namespaceName, userToolName1, data, comment);
			createUserTool(session, namespaceName, userToolName2, data, comment);

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

			createNamespace(session, namespaceName);
			String userToolId = createUserTool(session, namespaceName, userToolName, data, comment);

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
