

package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.CypherScriptExporter;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.neo4j.driver.internal.value.NullValue;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.Record;

import java.io.IOException;
import java.util.*;

import static org.junit.Assert.*;

public class GetQueryAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	private void createNamespace(Session session, String namespaceName) {
		session.writeTransaction(tx -> {
			tx.run("CALL deposit.create.namespace($namespaceName, '', '', $token)",
					Map.of("namespaceName", namespaceName, "token", token));
			tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)",
					Map.of("namespaceName", namespaceName, "token", token));
			ServerContext.setActiveNamespace(namespaceName, token);
			tx.commit();
			return null;
		});
	}

	private String createObject(Session session, String className) {
		return session.writeTransaction(tx -> {
			Result result = tx.run("CALL deposit.create.object($className, [], [], $token) YIELD node RETURN node.id AS objectId",
								   Map.of("className", className, "token", token));
			String objectId = result.single().get("objectId").asString();
			tx.commit();
			return objectId;
		});
	}

	private String createDescriptor(Session session, String objectId, String descriptorName, Object value) {
		return session.writeTransaction(tx -> {
			Result result = tx.run("CALL deposit.set.descriptor($objectId, $descriptorName, $value, $token) YIELD node RETURN node.id AS descriptorId",
				   Map.of("objectId", objectId, "descriptorName", descriptorName, "value", value, "token", token));
			String descriptorId = result.single().get("descriptorId").asString();
			tx.commit();
			return descriptorId;
		});
	}

	private String createRelationship(Session session, String objectId1, String label, String objectId2) {
		return session.writeTransaction(tx -> {
			Result result = tx.run("CALL deposit.create.relationship($objectId1, $objectId2, $label, null, $token) YIELD rel RETURN rel.id AS relationshipId",
					   Map.of("objectId1", objectId1, "objectId2", objectId2, "label", label, "token", token));
			String relId = result.single().get("relationshipId").asString();
			tx.commit();
			return relId;
		});
	}

	private static List<String[]> listOfLists(int groupSize, String... items) {
		List<String[]> result = new ArrayList<>();
		for (int i = 0; i < items.length; i += groupSize) {
			int end = Math.min(i + groupSize, items.length);
			String[] group = new String[end - i];
			System.arraycopy(items, i, group, 0, end - i);
			result.add(group);
		}
		return result;
	}

	private void assertQueryResult(Result queryResult, String key, int nColumns, String... items) {
		List<String[]> expectedRows = listOfLists(nColumns, items);
		int i = 0;
		while (queryResult.hasNext()) {
			List<Object> resultList = queryResult.next().get("result").asList();
			assertTrue("Number of returned results exceeded expected value.", i < expectedRows.size());
			String[] expected = expectedRows.get(i);
			for (int j = 0; j < nColumns; j++) {
				Object obj = resultList.get(j);
				Map<String, Object> entryMap = (Map<String, Object>) obj;
				Map.Entry<String, Object> entry = entryMap.entrySet().iterator().next();
				Object valueObj = entry.getValue();
				String value;
				if (valueObj instanceof Node node) {
					value = node.get(key) instanceof NullValue ? null : node.get(key).asString();
				} else if (valueObj instanceof Number || valueObj instanceof String) {
					value = valueObj.toString();
				} else {
					value = null;
				}
				assertEquals(expected[j], value);
			}
			i++;
		}
		assertEquals("Number of returned results doesn't match expected rows.", expectedRows.size(), i);
	}

	private List<Map<String, String>> createData(Session session, String namespaceName, List<String[]> headers, List<String[]> values, List<String[]> relationships) {
		/*
		 * headers:
		 *   class1, descriptor1,
		 *   class2, descriptor2,
		 *   ...
		 *
		 * values:
		 *   value1, value2, ...
		 *   value1, value2, ...
		 *   value1, value2, ...
		 *   ...
		 *
		 * relationships:
		 *   srcRow, srcClass, label, tgtRow, tgtClass
		 *   srcRow, srcClass, label, tgtRow, tgtClass
		 *   ...
		 *
		 * returns objectIds:
		 *   objectId = objectIds.get(row).get(className)
 		 */
		createNamespace(session, namespaceName);

		int nHeaders = headers.size();
		int nRows = values.size();
		List<Map<String, String>> objectIds = new ArrayList<>();
		for (int i = 0; i < nRows; i++) {
			Map<String, String> objectIdsRow = new HashMap<>();
			for (int j = 0; j < nHeaders; j++) {
				String className = headers.get(j)[0];
				String objectId = objectIdsRow.getOrDefault(className, null);
				if (objectId == null) {
					objectId = createObject(session, className);
					objectIdsRow.put(className, objectId);
				}
				String descriptor = headers.get(j)[1];
				String value = values.get(i)[j];
				if ((descriptor != null) && (value != null)) {
					createDescriptor(session, objectId, descriptor, value);
				}
			}
            objectIds.add(objectIdsRow);
		}

		int nRelationships = relationships.size();
		for (int i = 0; i < nRelationships; i++) {
			int srcRow = Integer.parseInt(relationships.get(i)[0]);
			String srcClass = relationships.get(i)[1];
			String label = relationships.get(i)[2];
			int tgtRow = Integer.parseInt(relationships.get(i)[3]);
			String tgtClass = relationships.get(i)[4];
			String srcId = objectIds.get(srcRow).get(srcClass);
			String tgtId = objectIds.get(tgtRow).get(tgtClass);
            createRelationship(session, srcId, label, tgtId);
		}

		return objectIds;
	}

	@Test
	public void testSimpleQuery() {
		try (Session session = driver.session()) {
			List<String[]> headers = listOfLists(2,
				"C1", null,
				"C2", null
			);
			List<String[]> values = listOfLists(2,
				null, null,
				null, null
			);
			List<String[]> relationships = listOfLists(5,
				"0", "C1", "contains", "0", "C2"
			);
			List<Map<String, String>> objectIds = createData(session, "TestNamespace", headers, values, relationships);
			Result queryResult = session.run(
					"CALL deposit.get.query('SELECT C1, C2 ORDER BY C2', $token) YIELD result RETURN result",
					Map.of("token", token)
			);
			assertQueryResult(queryResult, "id", 2,
				objectIds.get(0).get("C1"), objectIds.get(0).get("C2"),
				objectIds.get(1).get("C1"), null
			);
		}
	}

	@Test
	public void testDescriptors() {
		try (Session session = driver.session()) {
			List<String[]> headers = listOfLists(2,
				"C1", "D1",
				"C2", "D2"
			);
			List<String[]> values = listOfLists(2,
				"value1", "value2",
				"value3", "value4"
			);
			List<String[]> relationships = listOfLists(5,
				"0", "C1", "contains", "0", "C2",
				"0", "C1", "contains", "1", "C2"
			);
			List<Map<String, String>> objectIds = createData(session, "TestNamespace", headers, values, relationships);
			Result queryResult = session.run(
					"CALL deposit.get.query('SELECT C1.D1, C2.D2 ORDER BY C1.D1, C2.D2', $token) YIELD result RETURN result",
					Map.of("token", token)
			);
			assertQueryResult(queryResult, "value", 2,
				"value1", "value2",
				"value1", "value4",
				"value3", null
			);
		}
	}

	@Test
	public void testWhereClause() {
		try (Session session = driver.session()) {
			List<String[]> headers = listOfLists(2,
				"C1", "D1",
				"C2", "D2"
			);
			List<String[]> values = listOfLists(2,
				"value1", "value2",
				null, "value2",
				null, "value4"
			);
			List<String[]> relationships = listOfLists(5,
				"0", "C1", "contains", "0", "C2",
				"0", "C1", "contains", "1", "C2",
				"0", "C1", "contains", "2", "C2"
			);
			List<Map<String, String>> objectIds = createData(session, "TestNamespace", headers, values, relationships);
			Result queryResult = session.run(
					"CALL deposit.get.query('SELECT C1.D1, C2.D2 WHERE C2.D2 = \"value2\" ORDER BY C1.D1, C2.D2', $token) YIELD result RETURN result",
					Map.of("token", token)
			);
			assertQueryResult(queryResult, "value", 2,
				"value1", "value2",
				"value1", "value2",
				"value1", null, // TODO incorrect (?)
				null, null,
				null, null
			);
		}
	}

	@Test
	public void testRelationship() {
		try (Session session = driver.session()) {
			List<String[]> headers = listOfLists(2,
				"C1", "D1",
				"C2", "D2"
			);
			List<String[]> values = listOfLists(2,
				"value1", "value2",
				null, "value3",
				null, "value4",
				null, "value5"
			);
			List<String[]> relationships = listOfLists(5,
				"0", "C1", "contains", "0", "C2",
				"0", "C1", "disturbs", "1", "C2",
				"0", "C1", "disturbs", "2", "C2",
				"0", "C1", "contains", "3", "C2"
			);
			List<Map<String, String>> objectIds = createData(session, "TestNamespace", headers, values, relationships);
			Result queryResult = session.run(
					"CALL deposit.get.query('SELECT C1.D1, C2.D2 WHERE C1-disturbs->C2 ORDER BY C1.D1, C2.D2', $token) YIELD result RETURN result",
					Map.of("token", token)
			);

			assertQueryResult(queryResult, "value", 2,
				"value1", "value3",
				"value1", "value4",
					null, null,
					null, null,
					null, null
			);
		}
	}
	
	@Test
	public void testCount() {
		try (Session session = driver.session()) {
			List<String[]> headers = listOfLists(2,
				"C1", "D1",
				"C2", null
			);
			List<String[]> values = listOfLists(2,
				"value1", null,
				"value2", null,
				null, null,
				null, null,
				null, null
			);
			List<String[]> relationships = listOfLists(5,
				"0", "C1", "contains", "0", "C2",
				"0", "C1", "contains", "1", "C2",
				"1", "C1", "contains", "2", "C2",
				"1", "C1", "contains", "3", "C2",
				"1", "C1", "contains", "4", "C2"
			);
			List<Map<String, String>> objectIds = createData(session, "TestNamespace", headers, values, relationships);

			CypherScriptExporter exporter = new CypherScriptExporter(driver);
        	exporter.exportData();

			Result queryResult = session.run(
					"CALL deposit.get.query('SELECT C1.D1, COUNT(C2) AS CNT_2 WHERE C1.D1 = \"value1\" ORDER BY C1.D1', $token) YIELD result RETURN result",
					Map.of("token", token)
			);

			assertQueryResult(queryResult, "value", 2,
				"value1", "2"
			);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
