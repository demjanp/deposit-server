package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.CypherScriptExporter;
import deposit.common.ServerContext;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.neo4j.driver.Record;

import java.util.*;

import static org.junit.Assert.*;

public class GetQueryAPITest extends BaseNeo4jTest {

	private void createNamespace(Session session, String namespaceName) {
		session.writeTransaction(tx -> {
			tx.run("CALL deposit.create.namespace($namespaceName, '', '', '')",
					Map.of("namespaceName", namespaceName));
			tx.run("CALL deposit.set.activeNamespace($namespaceName, '')",
					Map.of("namespaceName", namespaceName));
			ServerContext.setActiveNamespace(namespaceName, "");
			tx.commit();
			return null;
		});
	}

	private String createObject(Session session, String className) {
		return session.writeTransaction(tx -> {
			Result result = tx.run("CALL deposit.create.object($className, [], [], '') YIELD node RETURN node.id AS objectId",
								   Map.of("className", className));
			String objectId = result.single().get("objectId").asString();
			tx.commit();
			return objectId;
		});
	}

	private String createDescriptor(Session session, String objectId, String descriptorName, Object value) {
		return session.writeTransaction(tx -> {
			Result result = tx.run("CALL deposit.set.descriptor($objectId, $descriptorName, $value, '') YIELD node RETURN node.id AS descriptorId",
				   Map.of("objectId", objectId, "descriptorName", descriptorName, "value", value));
			String descriptorId = result.single().get("descriptorId").asString();
			tx.commit();
			return descriptorId;
		});
	}

	private String createRelationship(Session session, String objectId1, String label, String objectId2) {
		return session.writeTransaction(tx -> {
			Result result = tx.run("CALL deposit.create.relationship($objectId1, $objectId2, $label, null, '') YIELD rel RETURN rel.id AS relationshipId",
					   Map.of("objectId1", objectId1, "objectId2", objectId2, "label", label));
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

	private void assertQueryResult(Result queryResult, boolean ordered, int nColumns, String... items) {
		List<String[]> expectedRows = listOfLists(nColumns, items);
		List<String[]> actualRows = new ArrayList<>();
		
		while (queryResult.hasNext()) {
			Record record = queryResult.next();
			String[] row = new String[nColumns];
			for (int j = 0; j < nColumns; j++) {
				Object valueObj = record.get(j).asObject();
				String value = (valueObj instanceof Number || valueObj instanceof String) ? valueObj.toString() : null;
				row[j] = value;
			}
			actualRows.add(row);
		}
		assertEquals("Number of returned results doesn't match expected rows.", expectedRows.size(), actualRows.size());
		for (String[] expected : expectedRows) {
			boolean matchFound = actualRows.stream().anyMatch(actual -> Arrays.equals(expected, actual));
			assertTrue("Expected row not found: " + Arrays.toString(expected), matchFound);
			//actualRows.removeIf(actual -> Arrays.equals(expected, actual));
		}
	}

	private void printQueryResult(Result queryResult) {
		while (queryResult.hasNext()) {
			Record record = queryResult.next();
			System.out.println(record);
		}
	}

	private static Number parseNumber(String input) {
		if (input == null) {
			return null;
		}
		try {
			return Integer.parseInt(input);
		} catch (NumberFormatException e) {
			try {
				return Double.parseDouble(input);
			} catch (NumberFormatException ex) {
				return null;
			}
		}
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
				Number numberValue = parseNumber(value);
				Object storedValue = numberValue == null ? value : numberValue;
				if ((descriptor != null) && (storedValue != null)) {
					createDescriptor(session, objectId, descriptor, storedValue);
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
	public void testSingleClassQuery() {
		try (Session session = driver.session()) {
			List<String[]> headers = listOfLists(2,
				"C1", null
			);
			List<String[]> values = listOfLists(1,
				null,
				null
			);
			List<String[]> relationships = new ArrayList<>();
			List<Map<String, String>> objectIds = createData(session, "TestNamespace", headers, values, relationships);
			Result queryResult = session.run(
					"CALL deposit.get.query('SELECT C1 ORDER BY C1', '') YIELD result RETURN result.C1.id AS C1"
			);
			assertQueryResult(queryResult, false, 1,
				objectIds.get(0).get("C1"),
				objectIds.get(1).get("C1")
			);
		}
	}

	@Test
	public void testTwoClassesQuery() {
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
					"CALL deposit.get.query('SELECT C1, C2 ORDER BY C1, C2', '') YIELD result RETURN result.C1.id AS C1, result.C2.id AS C2"
			);
			assertQueryResult(queryResult, false, 2,
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
					"CALL deposit.get.query('SELECT C1.D1, C2.D2 ORDER BY C1.D1, C2.D2', '') YIELD result RETURN result.`C1.D1`.value AS C1_D1, result.`C2.D2`.value AS C2_D2"
			);
			assertQueryResult(queryResult, false, 2,
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
					"CALL deposit.get.query('SELECT C1.D1, C2.D2 WHERE C2.D2 = \"value2\" ORDER BY C1.D1, C2.D2', '') YIELD result RETURN result.`C1.D1`.value AS C1_D1, result.`C2.D2`.value AS C2_D2"
			);
			assertQueryResult(queryResult, true, 2,
				"value1", "value2",
				"value1", "value2"
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
					"CALL deposit.get.query('SELECT C1.D1, C2.D2 WHERE C1-disturbs->C2 ORDER BY C1.D1, C2.D2', '') YIELD result RETURN result.`C1.D1`.value AS C1_D1, result.`C2.D2`.value AS C2_D2"
			);

			assertQueryResult(queryResult, true, 2,
				"value1", "value3",
				"value1", "value4"
			);
		}
	}

	@Test
	public void testRelationshipWithAlias() {
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
					"CALL deposit.get.query('SELECT C1.D1, C2.D2 AS alias WHERE C1-disturbs->alias ORDER BY C1.D1, alias', '') YIELD result RETURN result.`C1.D1`.value AS C1_D1, result.alias.value AS alias"
			);

			assertQueryResult(queryResult, true, 2,
				"value1", "value3",
				"value1", "value4"
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
			createData(session, "TestNamespace", headers, values, relationships);
			Result queryResult = session.run(
					"CALL deposit.get.query('SELECT C1.D1, COUNT(C2) AS CNT_2 WHERE C1.D1 = \"value1\" ORDER BY C1.D1', '') YIELD result " +
							"RETURN result.`C1.D1`.value AS C1_D1, result.CNT_2 AS CNT_2"
			);
			assertQueryResult(queryResult, true, 2,
					"value1", "2"
			);
		}
	}

	@Test
	public void testSelectWithAliasAndWhere() {
		try (Session session = driver.session()) {
			// Step 1: Prepare headers with classes and descriptors
			List<String[]> headers = listOfLists(2,
				"C1", "D1",
				"C2", "D2"
			);

			// Step 2: Prepare values for each row
			List<String[]> values = listOfLists(2,
				"value1", "value2", // Row 0: Should match the WHERE clause
				"value1", "value3", // Row 1: Does not match alias = "value2"
				"value2", "value2"  // Row 2: Does not match C1.D1 = "value1"
			);

			// Step 3: Define relationships between objects
			List<String[]> relationships = listOfLists(5,
				"0", "C1", "relatesTo", "0", "C2",
				"1", "C1", "relatesTo", "1", "C2",
				"2", "C1", "relatesTo", "2", "C2"
			);

			// Step 4: Create data in the Neo4j database
			List<Map<String, String>> objectIds = createData(session, "TestNamespace", headers, values, relationships);

			// Step 5: Run the Deposit query
			String query = "CALL deposit.get.query('SELECT C1.D1, C2.D2 AS alias WHERE C1.D1 = \"value1\" AND alias = \"value2\" ORDER BY C1.D1, C2.D2', '') YIELD result " +
						   "RETURN result.`C1.D1`.value AS C1_D1, result.alias.value AS alias";
			Result queryResult = session.run(query);

			// Step 6: Assert the results
			assertQueryResult(queryResult, true, 2,
				"value1", "value2"
			);
		}
	}

	@Test
	public void testSelectWithQuotedClassesAndDescriptors() {
		try (Session session = driver.session()) {
			// Step 1: Prepare headers with classes and descriptors, including quoted names
			List<String[]> headers = listOfLists(2,
				"C1", "D1",
				"Class 2", "D2",
				"Class 3", "Descr 3"
			);

			// Step 2: Prepare values for each row
			List<String[]> values = listOfLists(3,
				"value1", "value2", "value3", // Row 0
				"value1", "value2", "value4", // Row 1
				"value2", "value5", "value6"  // Row 2
			);

			// Step 3: Define relationships between objects
			List<String[]> relationships = listOfLists(5,
				"0", "C1", "relatesTo", "0", "Class 2",
				"0", "Class 2", "relatesTo", "0", "Class 3",
				"1", "C1", "relatesTo", "1", "Class 2",
				"1", "Class 2", "relatesTo", "1", "Class 3",
				"2", "C1", "relatesTo", "2", "Class 2",
				"2", "Class 2", "relatesTo", "2", "Class 3"
			);

			// Step 4: Create data in the Neo4j database
			List<Map<String, String>> objectIds = createData(session, "TestNamespace", headers, values, relationships);

			// Step 5: Run the Deposit query
			String query = "CALL deposit.get.query('SELECT C1.D1, \"Class 2\".D2, \"Class 3\".\"Descr 3\" ORDER BY C1.D1, \"Class 2\".D2, \"Class 3\".\"Descr 3\"', '') YIELD result " +
						   "RETURN result.`C1.D1`.value AS C1_D1, result.`Class 2.D2`.value AS Class2_D2, result.`Class 3.Descr 3`.value AS Class3_Descr3";
			Result queryResult = session.run(query);

			// Step 6: Assert the results
			assertQueryResult(queryResult, true, 3,
				"value1", "value2", "value3",
				"value1", "value2", "value4",
				"value2", "value5", "value6"
			);
		}
	}

	@Test
	public void testSelectWithDirectionalRelationship() {
		try (Session session = driver.session()) {
			// Step 1: Prepare headers with classes
			List<String[]> headers = listOfLists(2,
				"C1", null,
				"C2", null,
				"C3", null,
				"C4", null
			);

			// Step 2: Prepare values for each row
			List<String[]> values = listOfLists(4,
				null, null, null, null, // Row 0
				null, null, null, null, // Row 1
				null, null, null, null  // Row 2
			);

			// Step 3: Define relationships
			List<String[]> relationships = listOfLists(5,
				"0", "C1", "relatesTo", "0", "C2",
				"0", "C2", "contains", "0", "C3",
				"0", "C3", "relatesTo", "0", "C4",
				"1", "C1", "relatesTo", "1", "C2",
				"1", "C2", "contains", "1", "C4",
				"1", "C3", "relatesTo", "1", "C4",
				"2", "C1", "relatesTo", "2", "C2",
				"2", "C3", "contains", "2", "C2",
				"2", "C3", "relatesTo", "2", "C4"
			);

			List<Map<String, String>> objectIds = createData(session, "TestNamespace", headers, values, relationships);

			String query = "CALL deposit.get.query('SELECT C1, C2, C3, C4 WHERE C3<-contains-C2 ORDER BY C1, C2, C3, C4', '') YIELD result " +
						   "RETURN result.C1.id AS C1, result.C2.id AS C2, result.C3.id AS C3, result.C4.id AS C4";
			Result queryResult = session.run(query);

			String c1_id = objectIds.get(0).get("C1");
			String c2_id = objectIds.get(0).get("C2");
			String c3_id = objectIds.get(0).get("C3");
			String c4_id = objectIds.get(0).get("C4");

			assertQueryResult(queryResult, true, 4,
				c1_id, c2_id, c3_id, c4_id
			);
		}
	}

	@Test
	public void testSelectWithBidirectionalContainsRelationshipAndFilteredOutLabels() {
		try (Session session = driver.session()) {
			List<String[]> headers = listOfLists(2,
				"C1", null,
				"C2", null
			);

			List<String[]> values = listOfLists(2,
				null, null,
				null, null,
				null, null,
				null, null
			);

			List<String[]> relationships = listOfLists(5,
				"0", "C1", "contains", "0", "C2",
				"1", "C2", "contains", "1", "C1",
				"2", "C1", "relatesTo", "2", "C2",
				"3", "C1", "disturbs", "3", "C2"
			);

			List<Map<String, String>> objectIds = createData(session, "TestNamespace", headers, values, relationships);

			String query = "CALL deposit.get.query('SELECT C1, C2 WHERE C1-contains-C2 ORDER BY C1, C2', '') YIELD result " +
						   "RETURN result.C1.id AS C1, result.C2.id AS C2";
			Result queryResult = session.run(query);

			String c1_id_row_0 = objectIds.get(0).get("C1");
			String c2_id_row_0 = objectIds.get(0).get("C2");

			String c1_id_row_1 = objectIds.get(1).get("C1");
			String c2_id_row_1 = objectIds.get(1).get("C2");

			assertQueryResult(queryResult, false, 2,
				c1_id_row_0, c2_id_row_0,
				c1_id_row_1, c2_id_row_1
			);
		}
	}

	@Test
	public void testSelectWithCountDistinctAndContainsRelationship() {
		try (Session session = driver.session()) {
			List<String[]> headers = listOfLists(2,
				"C2", "D1",
				"C1", null
			);

			List<String[]> values = listOfLists(2,
				"value1", null, // Row 0
				"value2", null, // Row 1
				"value3", null,  // Row 2
				"value3", null  // Row 3
			);

			List<String[]> relationships = listOfLists(5,
				"0", "C1", "contains", "0", "C2", // C1 contains C2 (Row 0)
				"0", "C1", "contains", "1", "C2", // C1 contains C2 (Row 1)
				"0", "C1", "contains", "2", "C2", // C1 contains C2 (Row 2)
				"0", "C1", "contains", "3", "C2", // C1 contains C2 (Row 3)
				"1", "C1", "contains", "1", "C2"  // C1 (Row 1) contains C2 (Row 1)
			);

			List<Map<String, String>> objectIds = createData(session, "TestNamespace", headers, values, relationships);

			String query = "CALL deposit.get.query('SELECT C1, COUNT(DISTINCT C2.D1) AS COUNT_C2 WHERE C1-contains->C2 ORDER BY C1, COUNT_C2', '') YIELD result " +
						   "RETURN result.C1.id AS C1, result.COUNT_C2 AS COUNT_C2";
			Result queryResult = session.run(query);

			assertQueryResult(queryResult, false, 2,
					objectIds.get(0).get("C1"), "3",
					objectIds.get(1).get("C1"), "1"
			);
		}
	}

	@Test
	public void testSelectWithPercentileAndOrderBy() {
		try (Session session = driver.session()) {
			List<String[]> headers = listOfLists(2,
				"C1", "D1",
				"C2", "D2"
			);

			List<String[]> values = listOfLists(2,
				"value1", "10", // Row 0: C1.D1 = value1, C2.D2 = 10
				"value2", "20", // Row 1: C1.D1 = value1, C2.D2 = 20
				null, "30", // Row 2: C1.D1 = value1, C2.D2 = 30
				null, "40", // Row 3: C1.D1 = value2, C2.D2 = 40
				null, "50"  // Row 4: C1.D1 = value2, C2.D2 = 50
			);

			List<String[]> relationships = listOfLists(5,
				"0", "C1", "contains", "0", "C2", // Row 0: C1 contains C2
				"0", "C1", "contains", "1", "C2", // Row 1: C1 contains C2
				"0", "C1", "contains", "2", "C2", // Row 2: C1 contains C2
				"1", "C1", "contains", "3", "C2", // Row 3: C1 contains C2
				"1", "C1", "contains", "4", "C2"  // Row 4: C1 contains C2
			);

			List<Map<String, String>> objectIds = createData(session, "TestNamespace", headers, values, relationships);

			String query = "CALL deposit.get.query('SELECT C1.D1, PERCENTILE(C2.D2, 0.25) AS QUART1_D2 ORDER BY C1.D1, QUART1_D2', '') YIELD result " +
						   "RETURN result.`C1.D1`.value AS C1_D1, result.QUART1_D2 AS QUART1_D2";
			Result queryResult = session.run(query);

			assertQueryResult(queryResult, true, 2,
				"value1", "15.0", // 25th percentile of [10, 20, 30] is 15
					"value2", "42.5", // 25th percentile of [40, 50] is 42.5
					null, null
			);
		}
	}

	@Test
	public void testSelectWithSumAndOrderBy() {
		try (Session session = driver.session()) {
			List<String[]> headers = listOfLists(2,
				"C1", "D1",
				"C2", "D2"
			);

			List<String[]> values = listOfLists(2,
				"value1", "10", // Row 0: C1.D1 = value1, C2.D2 = 10
				"value2", "20", // Row 1: C1.D1 = value1, C2.D2 = 20
				null, "30", // Row 2: C1.D1 = value1, C2.D2 = 30
				null, "40", // Row 3: C1.D1 = value2, C2.D2 = 40
				null, "50"  // Row 4: C1.D1 = value2, C2.D2 = 50
			);

			List<String[]> relationships = listOfLists(5,
				"0", "C1", "contains", "0", "C2", // Row 0: C1 contains C2
				"0", "C1", "contains", "1", "C2", // Row 1: C1 contains C2
				"0", "C1", "contains", "2", "C2", // Row 2: C1 contains C2
				"1", "C1", "contains", "3", "C2", // Row 3: C1 contains C2
				"1", "C1", "contains", "4", "C2"  // Row 4: C1 contains C2
			);

			List<Map<String, String>> objectIds = createData(session, "TestNamespace", headers, values, relationships);

			String query = "CALL deposit.get.query('SELECT C1.D1, SUM(C2.D2) AS SUM_C2_D2 ORDER BY C1.D1, SUM_C2_D2', '') YIELD result " +
						   "RETURN result.`C1.D1`.value AS C1_D1, result.SUM_C2_D2 AS SUM_C2_D2";
			Result queryResult = session.run(query);

			assertQueryResult(queryResult, true, 2,
				"value1", "60", // SUM of [10, 20, 30] is 60
				"value2", "90", // SUM of [40, 50] is 90
				null, "0"
			);
		}
	}

	@Test
	public void testSelectWithComplexWhereClauseAndNotCondition() {
		try (Session session = driver.session()) {
			List<String[]> headers = listOfLists(2,
				"C1", "D1",
				"C2", "D2",
				"C3", "D3"
			);

			List<String[]> values = listOfLists(3,
				"value1", "10x", "value4",  // Row 0: C1.D1 = value1, C2.D2 = 10x, C3.D3 = value4
				"value2", "20x", "value3",  // Row 1: C1.D1 = value2, C2.D2 = 20x, C3.D3 = value3
				"value1", "value2", "value5" // Row 2: C1.D1 = value1, C2.D2 = value2, C3.D3 = value5
			);

			List<String[]> relationships = listOfLists(5,
				"0", "C1", "disturbs", "0", "C2",
				"0", "C2", "contains", "0", "C3",
				"1", "C1", "contains", "1", "C2",
				"1", "C2", "contains", "1", "C3",
				"2", "C1", "contains", "2", "C2",
				"2", "C2", "contains", "2", "C3"
			);

			List<Map<String, String>> objectIds = createData(session, "TestNamespace", headers, values, relationships);

			String query = "CALL deposit.get.query('SELECT C1.D1, C2.D2 WHERE (C1.D1 = \"value1\" OR C2.D2 = \"value2\") AND C1-contains->C2 AND NOT C3.D3 = \"value3\" ORDER BY C1.D1, C2.D2', '') YIELD result " +
						   "RETURN result.`C1.D1`.value AS C1_D1, result.`C2.D2`.value AS C2_D2";
			Result queryResult = session.run(query);

			assertQueryResult(queryResult, true, 2,
				"value1", "value2"  // Matches Row 2
			);
		}
	}

	@Test
	public void testSelectWithMtoNHopRelationship() {
		try (Session session = driver.session()) {
			List<String[]> headers = listOfLists(2,
				"C1", "D1",
				"C2", "D2",
				"M", null
			);

			List<String[]> values = listOfLists(3,
				"val1", "A", null, // Row 0
				"val2", "B", null, // Row 1
				"val3", "C", null, // Row 2
				"val4", "D", null,  // Row 3
				"val5", "E", null,  // Row 4
				"val6", "F", null,  // Row 5
				"val7", "G", null,  // Row 6
				"val8", "H", null  // Row 7
			);

			List<String[]> relationships = listOfLists(5,
				// 2-hop valid path: C1 -> C1 -> C2 (valid for the query)
				"0", "C1", "relatesTo", "0", "M",
				"0", "M", "relatesTo", "0", "C2",

				// 3-hop valid path: C1 -> C1 -> C1 -> C2 (valid for the query)
				"1", "C1", "relatesTo", "1", "M",
				"1", "M", "relatesTo", "2", "M",
				"2", "M", "relatesTo", "2", "C2",

				// 1-hop path: C1 -> C2
				"3", "C1", "relatesTo", "3", "C2",

				// 5-hop path
				"4", "C1", "relatesTo", "4", "M",
				"4", "M", "relatesTo", "5", "M",
				"5", "M", "relatesTo", "6", "M",
				"6", "M", "relatesTo", "7", "M",
				"7", "M", "relatesTo", "7", "C2"
			);

			List<Map<String, String>> objectIds = createData(session, "TestNamespace", headers, values, relationships);

			String query = "CALL deposit.get.query('SELECT C1.D1, C2.D2 WHERE C1-*2..3->C2 ORDER BY C1.D1, C2.D2', '') YIELD result " +
						   "RETURN result.`C1.D1`.value AS C1_D1, result.`C2.D2`.value AS C2_D2";
			Result queryResult = session.run(query);

			assertQueryResult(queryResult, false, 2,
				"val1", "A", // 2-hop valid result
				"val2", "C"  // 3-hop valid result
			);
		}
	}

	@Test
	public void testSelectWithMultiHopRelationship() {
		try (Session session = driver.session()) {
			List<String[]> headers = listOfLists(2,
				"C1", "D1",
				"C2", "D2",
				"M", null
			);

			List<String[]> values = listOfLists(3,
				"val1", "A", null, // Row 0
				"val2", "B", null, // Row 1
				"val3", "C", null, // Row 2
				"val4", "D", null,  // Row 3
				"val5", "E", null,  // Row 4
				"val6", "F", null,  // Row 5
				"val7", "G", null,  // Row 6
				"val8", "H", null  // Row 7
			);

			List<String[]> relationships = listOfLists(5,
				// 2-hop
				"0", "C1", "relatesTo", "0", "M",
				"0", "M", "relatesTo", "0", "C2",

				// 3-hop
				"2", "C2", "relatesTo", "1", "M",
				"1", "M", "relatesTo", "2", "M",
				"2", "M", "relatesTo", "1", "C1",

				// 1-hop
				"3", "C1", "relatesTo", "3", "C2",

				// 4-hop
				"7", "C2", "relatesTo", "4", "M",
				"4", "M", "relatesTo", "5", "M",
				"5", "M", "relatesTo", "6", "M",
				"6", "M", "relatesTo", "4", "C1"
			);

			List<Map<String, String>> objectIds = createData(session, "TestNamespace", headers, values, relationships);

			String query = "CALL deposit.get.query('SELECT C1.D1, C2.D2 WHERE C1<-*-C2 ORDER BY C1.D1, C2.D2', '') YIELD result " +
						   "RETURN result.`C1.D1`.value AS C1_D1, result.`C2.D2`.value AS C2_D2";
			Result queryResult = session.run(query);

			assertQueryResult(queryResult, false, 2,
				"val2", "C", // 2-hop valid result
				"val5", "H"  // 3-hop valid result
			);
		}
	}

}
