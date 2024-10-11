package deposit.service.query;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class QueryTranslatorTest {

	@Test
	public void testSingleClass() {
		// Given a simple query with class and descriptor
		String depositQuery = "SELECT C1";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
						  "RETURN o_C1 AS C1";

		Map<String, String> expectedReturnedKeys = Map.of(
				"C1", "C1"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testClassAndDescriptor() {
		// Given a simple query with class and descriptor
		String depositQuery = "SELECT C1.D1";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'})\n" +
				"RETURN d_C1_D1 AS C1_D1";

		Map<String, String> expectedReturnedKeys = Map.of(
				"C1_D1", "C1.D1"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testTwoDescriptors() {
		// Given a simple query with class and descriptor
		String depositQuery = "SELECT C1.D1, C1.D2";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D2:Descriptor {name: 'D2'})\n" +
				"RETURN d_C1_D1 AS C1_D1, d_C1_D2 AS C1_D2";

		Map<String, String> expectedReturnedKeys = Map.of(
				"C1_D1", "C1.D1",
				"C1_D2", "C1.D2"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testAlias() {
		// Given a simple query with class and descriptor
		String depositQuery = "SELECT C1.D1 AS alias";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_alias:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_alias)-[:HAS_DESCRIPTOR]-(d_alias:Descriptor {name: 'D1'})\n" +
				"RETURN d_alias AS alias";

		Map<String, String> expectedReturnedKeys = Map.of(
				"alias", "alias"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testWhere() {
		// Given a simple query with class and descriptor
		String depositQuery = "SELECT C1.D1, C2.D2 AS alias WHERE C1.D1 = 'value1' AND alias = 'value2'";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:RELATED_TO*]-(o_alias:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
				"OPTIONAL MATCH (o_alias)-[:HAS_DESCRIPTOR]-(d_alias:Descriptor {name: 'D2'})\n" +
				"WITH o_alias, o_C1, d_C1_D1, d_alias\n" +
				"WHERE (d_C1_D1.value = 'value1' AND d_alias.value = 'value2')\n" +
				"RETURN d_C1_D1 AS C1_D1, d_alias AS alias";

		Map<String, String> expectedReturnedKeys = Map.of(
			"C1_D1", "C1.D1",
			"alias", "alias"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testC2InWhere() {
		// Given a simple query with class and descriptor
		String depositQuery = "SELECT C1 WHERE C1.D1 = 'value1' AND C2.D2 = 'value2'";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:RELATED_TO*]-(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
				"OPTIONAL MATCH (o_C2)-[:HAS_DESCRIPTOR]-(d_C2_D2:Descriptor {name: 'D2'})\n" +
				"WITH o_C2, o_C1, d_C1_D1, d_C2_D2\n" +
				"WHERE (d_C1_D1.value = 'value1' AND d_C2_D2.value = 'value2')\n" +
				"RETURN o_C1 AS C1";

		Map<String, String> expectedReturnedKeys = Map.of(
			"C1", "C1"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testQuoted() {
		// Given a query with a relationship between two classes
		String depositQuery = "SELECT C1.D1, \"Class 2\".D2, \"Class 3\".\"Descr 3\"";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:RELATED_TO*]-(o_Class_2:Object)-[:MEMBER_OF]->(:Class {name: 'Class 2'})\n" +
				"OPTIONAL MATCH (o_Class_2)-[:HAS_DESCRIPTOR]-(d_Class_2_D2:Descriptor {name: 'D2'})\n" +
				"OPTIONAL MATCH (o_C1)-[:RELATED_TO*]-(o_Class_3:Object)-[:MEMBER_OF]->(:Class {name: 'Class 3'})\n" +
				"OPTIONAL MATCH (o_Class_3)-[:HAS_DESCRIPTOR]-(d_Class_3_Descr_3:Descriptor {name: 'Descr 3'})\n" +
				"RETURN d_C1_D1 AS C1_D1, d_Class_2_D2 AS Class_2_D2, d_Class_3_Descr_3 AS Class_3_Descr_3";

		Map<String, String> expectedReturnedKeys = Map.of(
			"C1_D1", "C1.D1",
			"Class_2_D2", "Class 2.D2",
			"Class_3_Descr_3", "Class 3.Descr 3"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testOutgoingRelationship() {
		String depositQuery = "SELECT C1, C2 WHERE C1-contains->C2";
		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO {label: 'contains'}]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
				"WITH o_C2, o_C1, r_C1_C2\n" +
				"WHERE (r_C1_C2 IS NOT NULL)\n" +
				"RETURN o_C1 AS C1, o_C2 AS C2";
		assertEquals(expected, cypher);
	}
	
	@Test
	public void testIncomingRelationship() {
		String depositQuery = "SELECT C1, C2, C3, C4 WHERE C3<-contains-C2";
		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:RELATED_TO*]-(o_C3:Object)-[:MEMBER_OF]->(:Class {name: 'C3'})\n" +
				"OPTIONAL MATCH (o_C1)-[:RELATED_TO*]-(o_C4:Object)-[:MEMBER_OF]->(:Class {name: 'C4'})\n" +
				"OPTIONAL MATCH (o_C3)<-[r_C3_C2:RELATED_TO {label: 'contains'}]-(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
				"WITH o_C4, o_C3, o_C2, o_C1, r_C3_C2\n" +
				"WHERE (r_C3_C2 IS NOT NULL)\n" +
				"RETURN o_C1 AS C1, o_C2 AS C2, o_C3 AS C3, o_C4 AS C4";
		assertEquals(expected, cypher);
	}
	
	@Test
	public void testUndirectedRelationship() {
		String depositQuery = "SELECT C1, C2 WHERE C1-contains-C2";
		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO {label: 'contains'}]-(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
				"WITH o_C2, o_C1, r_C1_C2\n" +
				"WHERE (r_C1_C2 IS NOT NULL)\n" +
				"RETURN o_C1 AS C1, o_C2 AS C2";
		assertEquals(expected, cypher);
	}

	@Test
	public void testCount() {
		// Given a query with a relationship between two classes
		String depositQuery = "SELECT C1.D1, COUNT(C2) WHERE C1-contains->C2";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'})\n" +
				"OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO {label: 'contains'}]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
				"WITH o_C2, o_C1, d_C1_D1, r_C1_C2\n" +
				"WHERE (r_C1_C2 IS NOT NULL)\n" +
				"RETURN d_C1_D1 AS C1_D1, COUNT(o_COUNT_C2) AS COUNT_C2";

		Map<String, String> expectedReturnedKeys = Map.of(
			"C1_D1", "C1.D1",
			"COUNT_C2", "COUNT_C2"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testCountDistinct() {
		// Given a query with a relationship between two classes
		String depositQuery = "SELECT C1.D1, COUNT(DISTINCT C2) WHERE C1-contains->C2";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'})\n" +
				"OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO {label: 'contains'}]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
				"WITH o_C2, o_C1, d_C1_D1, r_C1_C2\n" +
				"WHERE (r_C1_C2 IS NOT NULL)\n" +
				"RETURN d_C1_D1 AS C1_D1, COUNT(DISTINCT o_COUNT_C2) AS COUNT_C2";

		Map<String, String> expectedReturnedKeys = Map.of(
			"C1_D1", "C1.D1",
			"COUNT_C2", "COUNT_C2"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testPercentile() {
		// Given a query with a relationship between two classes
		String depositQuery = "SELECT C1.D1, PERCENTILE(C2.D2, 0.25) AS QUART1_D2 WHERE C1-contains->C2";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'})\n" +
				"OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO {label: 'contains'}]->(o_QUART1_D2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
				"OPTIONAL MATCH (o_QUART1_D2)-[:HAS_DESCRIPTOR]-(d_QUART1_D2:Descriptor {name: 'D2'})\n" +
				"WITH o_QUART1_D2, o_C1, d_C1_D1, d_QUART1_D2, r_C1_C2\n" +
				"WHERE (r_C1_C2 IS NOT NULL)\n" +
				"RETURN d_C1_D1 AS C1_D1, percentileCont(d_QUART1_D2.value, 0.25) AS QUART1_D2";

		Map<String, String> expectedReturnedKeys = Map.of(
			"C1_D1", "C1.D1",
			"QUART1_D2", "QUART1_D2"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testSum() {
		// Given a query with a relationship between two classes
		String depositQuery = "SELECT C1.D1, SUM(C2.D2) WHERE C1-contains->C2";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'})\n" +
				"OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO {label: 'contains'}]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
				"OPTIONAL MATCH (o_C2)-[:HAS_DESCRIPTOR]-(d_SUM_C2_D2:Descriptor {name: 'D2'})\n" +
				"WITH o_C2, o_C1, d_C1_D1, d_SUM_C2_D2, r_C1_C2\n" +
				"WHERE (r_C1_C2 IS NOT NULL)\n" +
				"RETURN d_C1_D1 AS C1_D1, SUM(d_SUM_C2_D2.value) AS SUM_C2_D2";

		Map<String, String> expectedReturnedKeys = Map.of(
			"C1_D1", "C1.D1",
			"SUM_C2_D2", "SUM_C2_D2"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testAliasAndRelationship() {
		// Given a query with an alias and a relationship between two classes
		String depositQuery = "SELECT C1.D1, C2 AS alias WHERE C1-contains->alias";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'})\n" +
				"OPTIONAL MATCH (o_C1)-[r_C1_alias:RELATED_TO {label: 'contains'}]->(o_alias:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
				"WITH o_alias, o_C1, d_C1_D1, r_C1_alias\n" +
				"WHERE (r_C1_alias IS NOT NULL)\n" +
				"RETURN d_C1_D1 AS C1_D1, o_alias AS alias";

		Map<String, String> expectedReturnedKeys = Map.of(
			"C1_D1", "C1.D1",
			"alias", "alias"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testWhereAndNotCondition() {
		// Given a query with a NOT condition in the WHERE clause
		String depositQuery = "SELECT C1.D1 WHERE NOT (C1.D1 = 'value1')";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'})\n" +
				"WITH o_C1, d_C1_D1\n" +
				"WHERE (NOT (d_C1_D1.value = 'value1'))\n" +
				"RETURN d_C1_D1 AS C1_D1";

		Map<String, String> expectedReturnedKeys = Map.of(
			"C1_D1", "C1.D1"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testNestedConditions() {
		// Given a query with nested conditions in the WHERE clause
		String depositQuery = "SELECT C1.D1, C2.D2 WHERE (C1.D1 = 'value1' OR C2.D2 = 'value2') AND (C1-contains->C2) AND NOT (C3.D3 = 'value3')";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:RELATED_TO*]-(o_C3:Object)-[:MEMBER_OF]->(:Class {name: 'C3'})\n" +
				"OPTIONAL MATCH (o_C3)-[:HAS_DESCRIPTOR]-(d_C3_D3:Descriptor {name: 'D3'})\n" +
				"OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO {label: 'contains'}]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
				"OPTIONAL MATCH (o_C2)-[:HAS_DESCRIPTOR]-(d_C2_D2:Descriptor {name: 'D2'})\n" +
				"WITH o_C3, o_C2, o_C1, d_C1_D1, d_C2_D2, d_C3_D3, r_C1_C2\n" +
				"WHERE ((d_C1_D1.value = 'value1' OR d_C2_D2.value = 'value2') AND (r_C1_C2 IS NOT NULL) AND NOT (d_C3_D3.value = 'value3'))\n" +
				"RETURN d_C1_D1 AS C1_D1, d_C2_D2 AS C2_D2";


		Map<String, String> expectedReturnedKeys = Map.of(
			"C1_D1", "C1.D1",
			"C2_D2", "C2.D2"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testHopRelationship1() {
		// Given a query with multi-hop Relationships
		String depositQuery = "SELECT C1.D1, C2.D2 WHERE C1-*1..3->C2";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'})\n" +
				"OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO*1..3]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
				"OPTIONAL MATCH (o_C2)-[:HAS_DESCRIPTOR]-(d_C2_D2:Descriptor {name: 'D2'})\n" +
				"WITH o_C2, o_C1, d_C1_D1, d_C2_D2, r_C1_C2\n" +
				"WHERE (r_C1_C2 IS NOT NULL)\n" +
				"RETURN d_C1_D1 AS C1_D1, d_C2_D2 AS C2_D2";

		Map<String, String> expectedReturnedKeys = Map.of(
			"C1_D1", "C1.D1",
			"C2_D2", "C2.D2"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testHopRelationship2() {
		// Given a query with multi-hop GSRelationships
		String depositQuery = "SELECT C1, C2 WHERE C1-contains*->C2";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO* {label: 'contains'}]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
				"WITH o_C2, o_C1, r_C1_C2\n" +
				"WHERE (r_C1_C2 IS NOT NULL)\n" +
				"RETURN o_C1 AS C1, o_C2 AS C2";

		Map<String, String> expectedReturnedKeys = Map.of(
			"C1", "C1",
			"C2", "C2"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testHopRelationship3() {
		// Given a query with multi-hop GSRelationships
		String depositQuery = "SELECT C1, C2 WHERE C1-contains*1..->C2";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO*1.. {label: 'contains'}]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
				"WITH o_C2, o_C1, r_C1_C2\n" +
				"WHERE (r_C1_C2 IS NOT NULL)\n" +
				"RETURN o_C1 AS C1, o_C2 AS C2";

		Map<String, String> expectedReturnedKeys = Map.of(
			"C1", "C1",
			"C2", "C2"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testHopRelationship4() {
		// Given a query with multi-hop GSRelationships
		String depositQuery = "SELECT C1, C2 WHERE C1-contains*..3->C2";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO*..3 {label: 'contains'}]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
				"WITH o_C2, o_C1, r_C1_C2\n" +
				"WHERE (r_C1_C2 IS NOT NULL)\n" +
				"RETURN o_C1 AS C1, o_C2 AS C2";

		Map<String, String> expectedReturnedKeys = Map.of(
			"C1", "C1",
			"C2", "C2"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testHopRelationship5() {
		// Given a query with multi-hop GSRelationships
		String depositQuery = "SELECT C1, C2 WHERE C1-contains*1..3->C2";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO*1..3 {label: 'contains'}]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
				"WITH o_C2, o_C1, r_C1_C2\n" +
				"WHERE (r_C1_C2 IS NOT NULL)\n" +
				"RETURN o_C1 AS C1, o_C2 AS C2";

		Map<String, String> expectedReturnedKeys = Map.of(
			"C1", "C1",
			"C2", "C2"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testAnyRelationship() {
		// Given a query with multi-hop GSRelationships
		String depositQuery = "SELECT C1, C2 WHERE C1-->C2";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
				"WITH o_C2, o_C1, r_C1_C2\n" +
				"WHERE (r_C1_C2 IS NOT NULL)\n" +
				"RETURN o_C1 AS C1, o_C2 AS C2";

		Map<String, String> expectedReturnedKeys = Map.of(
			"C1", "C1",
			"C2", "C2"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testHopsAnyRelationship() {
		// Given a query with multi-hop GSRelationships
		String depositQuery = "SELECT C1, C2 WHERE C1-*->C2";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		// Expected Cypher query
		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[r_C1_C2:RELATED_TO*]->(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
				"WITH o_C2, o_C1, r_C1_C2\n" +
				"WHERE (r_C1_C2 IS NOT NULL)\n" +
				"RETURN o_C1 AS C1, o_C2 AS C2";

		Map<String, String> expectedReturnedKeys = Map.of(
			"C1", "C1",
			"C2", "C2"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testOrderByDescriptors() {
		String depositQuery = "SELECT C1.D1, C2.D2 AS alias WHERE C1.D1 = 'value1' ORDER BY C1.D1, alias DESC";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:RELATED_TO*]-(o_alias:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
				"OPTIONAL MATCH (o_alias)-[:HAS_DESCRIPTOR]-(d_alias:Descriptor {name: 'D2'})\n" +
				"WITH o_alias, o_C1, d_C1_D1, d_alias\n" +
				"WHERE (d_C1_D1.value = 'value1')\n" +
				"RETURN d_C1_D1 AS C1_D1, d_alias AS alias\n" +
				"ORDER BY C1_D1 ASC, alias DESC";

		Map<String, String> expectedReturnedKeys = Map.of(
			"C1_D1", "C1.D1",
			"alias", "alias"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}

	@Test
	public void testOrderByClass() {
		String depositQuery = "SELECT C1.D1, C2 WHERE C1.D1 = 'value1' ORDER BY C1.D1, C2 DESC";

		QueryTranslator.QueryTranslatorResult result = QueryTranslator.translateToCypher(depositQuery);
		String cypher = result.getCypherQuery();
		Map<String, String> returnedKeys = result.getReturnedKeys();

		String expected = "MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(o_C1:Object)-[:MEMBER_OF]->(:Class {name: 'C1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:HAS_DESCRIPTOR]-(d_C1_D1:Descriptor {name: 'D1'})\n" +
				"OPTIONAL MATCH (o_C1)-[:RELATED_TO*]-(o_C2:Object)-[:MEMBER_OF]->(:Class {name: 'C2'})\n" +
				"WITH o_C2, o_C1, d_C1_D1\n" +
				"WHERE (d_C1_D1.value = 'value1')\n" +
				"RETURN d_C1_D1 AS C1_D1, o_C2 AS C2\n" +
				"ORDER BY C1_D1 ASC, ID(C2) DESC";

		Map<String, String> expectedReturnedKeys = Map.of(
			"C1_D1", "C1.D1",
			"C2", "C2"
		);

		assertEquals(expected, cypher);
		assertEquals(expectedReturnedKeys, returnedKeys);
	}
}
