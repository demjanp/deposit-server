package deposit.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import deposit.BaseNeo4jTest;
import deposit.common.GraphSONData;
import deposit.common.ServerContext;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class GetDataAPITest extends BaseNeo4jTest {

	private String getClassId(Session session, String className) {
		return session.writeTransaction(tx -> {
			Result result = tx.run("CALL deposit.get.class($className, '') YIELD node RETURN node.id AS classId",
								   Map.of("className", className));
			String classId = result.single().get("classId").asString();
			tx.commit();
			return classId;
		});
	}

	private void assertQueryResult(Result queryResult, Set<String> expectedValues) {
		Set<String> foundValues = new HashSet<>();
		Set<String> expectedResources = new HashSet<>();
		Set<String> foundResources = new HashSet<>();
		while (queryResult.hasNext()) {
			Map<String, Object> data = queryResult.next().get("data").asMap();
			Map<String, Object> graph = (Map<String, Object>) data.get("graph");
			for (String resource : (List<String>) data.get("resources")) {
				String[] parts = resource.split("-");
				expectedResources.add(parts[0] + ".tmp");
			}
			for (Map<String, Object> node : (List<Map<String, Object>>) graph.get("nodes")) {
				if (node.get("label").equals("Descriptor")) {
					Map<String, Object> properties = (Map<String, Object>) node.get("properties");
					Object valueObject = properties.get("value");
					if (valueObject != null) {
						String value = valueObject.toString();
						if (value.endsWith(".tmp")) {
							assertTrue("Unexpected resource found: " + value, expectedResources.contains(value));
							foundResources.add(value);
						} else {
							assertTrue("Unexpected value found: " + value, expectedValues.contains(value));
							foundValues.add(value);
						}
					}
				}
			}
		}
		assertEquals("Number of returned values doesn't match the expected number.", expectedValues.size(), foundValues.size());
		assertEquals("Number of returned resources doesn't match the expected number.", expectedResources.size(), foundResources.size());
	}

	private void printQueryResult(Result queryResult) {
		System.out.println("");
		while (queryResult.hasNext()) {
			Map<String, Object> data = queryResult.next().get("data").asMap();
			Map<String, Object> graph = (Map<String, Object>) data.get("graph");
			for (Map<String, Object> node : (List<Map<String, Object>>) graph.get("nodes")) {
				if (node.get("label").equals("Descriptor")) {
					Map<String, Object> properties = (Map<String, Object>) node.get("properties");
					Object value = properties.get("value");
					if (value != null) {
						System.out.println(value.toString());
					}
				}
			}
		}
	}
	
	private static String encodeDataToJson(Map<String, Object> data) throws IOException {
		Set<String> filterProps = Set.of(
			"createdAt", "createdBy", "updatedAt", "updatedBy", "weight", "order", "comment", "position"
		);

		List<Map<String, Object>> nodes = new ArrayList<>();
		for (Map<String, Object> node : (List<Map<String, Object>>) data.get("nodes")) {
			Long id = (Long) node.get("id");
			String label = node.get("label").toString();
			Map<String, Object> properties = new HashMap<>();
			for (Map.Entry<String, Object> entry : ((Map<String, Object>) node.get("properties")).entrySet()) {
				String key = entry.getKey();
				if (!filterProps.contains(key)) {
					properties.put(key, entry.getValue());
				}
			}
			nodes.add(Map.of(
				"id", id,
				"label", label,
				"properties", properties
			));
		}
		List<Map<String, Object>> edges = new ArrayList<>();
		for (Map<String, Object> edge : (List<Map<String, Object>>) data.get("edges")) {
			Long id = (Long) edge.get("id");
			Long inV = (Long) edge.get("inV");
			Long outV = (Long) edge.get("outV");
			String type = edge.get("type").toString();
			Map<String, Object> properties = new HashMap<>();
			for (Map.Entry<String, Object> entry : ((Map<String, Object>) edge.get("properties")).entrySet()) {
				String key = entry.getKey();
				if (!filterProps.contains(key)) {
					properties.put(key, entry.getValue());
				}
			}
			edges.add(Map.of(
					"id", id,
					"inV", inV,
					"outV", outV,
					"type", type,
					"properties", properties
			));
		}
		data = new HashMap<>(Map.of(
			"nodes", nodes,
			"edges", edges
		));

		ObjectMapper objectMapper = new ObjectMapper();
		return objectMapper.writeValueAsString(data);
	}
	
	private static Map<String, Object> decodeJsonToData(String jsonData) throws IOException {
		ObjectMapper objectMapper = new ObjectMapper();
		return objectMapper.readValue(jsonData, new TypeReference<Map<String, Object>>() {});
	}
	
	// Country-City-Company-Person
	private Map<String, String> createDataA(Session session, String namespaceName, String versioningType) {
		return createDataFromString(session, namespaceName, versioningType, "", """
			1(Country,Austria)
				-contains-> 2(City,Vienna)
					-contains-> 3(Company,Velocipeds)
						-competes_with-> 4(Company,Voleyballs)
						-competes_with-> 5(Company,Gamma)
						<-employee_of- 6(Person,Adam)
							-citizen_of-> 1(Country,Austria)
							-lives_in-> 2(City,Vienna)
							-friend_of-> 7(Person,Alex)
						<-employee_of- 7(Person,Alex)
							-citizen_of-> 1(Country,Austria)
							-lives_in-> 8(City,Linz)
							-friend_of-> 9(Person,Hugo)
							-friend_of-> 10(Person,Lisa)
					-contains-> 4(Company,Voleyballs)
						<-employee_of- 11(Person,Anna)
							-citizen_of-> 1(Country,Austria)
							-lives_in-> 2(City,Vienna)
						<-employee_of- 12(Person,Ben)
							-citizen_of-> 13(Country,Belgium)
							-lives_in-> 14(City,Ghent)
				-contains-> 8(City,Linz)
					-contains-> 15(Company,Logs)
						<-employee_of- 16(Person,Clara)
							-citizen_of-> 1(Country,Austria)
							-lives_in-> 8(City,Linz)
						<-employee_of- 17(Person,Ella)
							-citizen_of-> 1(Country,Austria)
							-lives_in-> 8(City,Linz)
					-contains-> 18(Company,Lentils)
						<-employee_of- 19(Person,Emma)
							-citizen_of-> 1(Country,Austria)
							-lives_in-> 8(City,Linz)
							-friend_of-> 20(Person,Mia)
						<-employee_of- 21(Person,Eva)
							-citizen_of-> 22(Country,Czechoslovakia)
							-lives_in-> 23(City,Prague)
							-friend_of-> 19(Person,Emma)
			13(Country,Belgium)
				-contains-> 14(City,Ghent)
					-contains-> 5(Company,Gamma)
						-competes_with-> 24(Company,Gothic)
						<-employee_of- 9(Person,Hugo)
							-citizen_of-> 13(Country,Belgium)
							-lives_in-> 14(City,Ghent)
						<-employee_of- 25(Person,Ian)
							-citizen_of-> 13(Country,Belgium)
							-lives_in-> 14(City,Ghent)
					-contains-> 24(Company,Gothic)
						-competes_with-> 26(Company,Beta)
						<-employee_of- 27(Person,Julia)
							-citizen_of-> 13(Country,Belgium)
							-lives_in-> 14(City,Ghent)
						<-employee_of- 28(Person,Leo)
							-citizen_of-> 13(Country,Belgium)
							-lives_in-> 14(City,Ghent)
				-contains-> 29(City,Antwerp)
					-contains-> 30(Company,Alpha)
						<-employee_of- 10(Person,Lisa)
							-citizen_of-> 13(Country,Belgium)
							-lives_in-> 29(City,Antwerp)
						<-employee_of- 31(Person,Luca)
							-citizen_of-> 13(Country,Belgium)
							-lives_in-> 29(City,Antwerp)
					-contains-> 32(Company,Automobiles)
						<-employee_of- 33(Person,Mark)
							-citizen_of-> 13(Country,Belgium)
							-lives_in-> 29(City,Antwerp)
							-owns-> 34(Car,Toyota)
						<-employee_of- 35(Person,Max)
							-citizen_of-> 13(Country,Belgium)
							-lives_in-> 29(City,Antwerp)
							-owns-> 36(Car,Peugeot)
			22(Country,Czechoslovakia)
				-contains-> 23(City,Prague)
					-contains-> 37(Company,Pilsner)
						<-employee_of- 20(Person,Mia)
							-citizen_of-> 22(Country,Czechoslovakia)
							-lives_in-> 23(City,Prague)
							-friend_of-> 21(Person,Eva)
						<-employee_of- 38(Person,Nora)
							-citizen_of-> 22(Country,Czechoslovakia)
							-lives_in-> 23(City,Prague)
					-contains-> 39(Company,Papermill)
						<-employee_of- 40(Person,Paul)
							-citizen_of-> 22(Country,Czechoslovakia)
							-lives_in-> 23(City,Prague)
						<-employee_of- 41(Person,Rita)
							-citizen_of-> 22(Country,Czechoslovakia)
							-lives_in-> 23(City,Prague)
				-contains-> 42(City,Bratislava)
					-contains-> 26(Company,Beta)
						<-employee_of- 43(Person,Sara)
							-citizen_of-> 22(Country,Czechoslovakia)
							-lives_in-> 2(City,Vienna)
						<-employee_of- 44(Person,Simon)
							-citizen_of-> 22(Country,Czechoslovakia)
							-lives_in-> 42(City,Bratislava)
					-contains-> 45(Company,Bakery)
						<-employee_of- 46(Person,Tom)
							-citizen_of-> 22(Country,Czechoslovakia)
							-lives_in-> 42(City,Bratislava)
						<-employee_of- 47(Person,Zoe)
							-citizen_of-> 22(Country,Czechoslovakia)
							-lives_in-> 42(City,Bratislava)
							-owns-> 48(Car,Skoda)
			"""
		);
	}
	
	// Area - Feature - Find
	private Map<String, String> createDataB(Session session, String namespaceName, String versioningType) {
		createNamespace(session, namespaceName, versioningType, "");
		Map<String, String> objectIdMap = new HashMap<>();
		for (int i = 1; i <= 3; i++) {
			String areaName = String.valueOf(i);
			String areaId = createObject(session, "Area", "");
			createDescriptor(session, areaId, "Name", String.valueOf(i), "");
			objectIdMap.put(areaName, areaId);
			for (int j = 1; j <= 6; j++) {
				String featureName = areaName + "." + j;
				String featureId = createObject(session, "Feature", "");
				createDescriptor(session, featureId, "Name", String.valueOf(j), "");
				createRelationship(session, areaId, "contains", featureId, 0.0, "");
				objectIdMap.put(featureName, featureId);
				for (int k = 1; k <= 3; k++) {
					String findName = featureName + "." + k;
					String findId = createObject(session, "Find", "");
					createDescriptor(session, findId, "Name", String.valueOf(k), "");
					createRelationship(session, featureId, "contains", findId, 0.0, "");
					objectIdMap.put(findName, findId);
				}
			}
		}
		String srcId = objectIdMap.get("2.2");
		String tgtId = objectIdMap.get("2.3");
		createRelationship(session, srcId, "disturbs", tgtId, 0.0, "");
		srcId = objectIdMap.get("2.3");
		tgtId = objectIdMap.get("2.4");
		createRelationship(session, srcId, "disturbs", tgtId, 0.0, "");
		return objectIdMap;
	}
	
	// Country-City-Company-Person
	private Map<String, String> createDataC(Session session, String namespaceName, String versioningType) {
		return createDataFromString(session, namespaceName, versioningType, "", """
				1(Country,Belgium)
					-contains-> 2(City,Antwerp)
						-contains-> 3(Company,Alpha)
							<-employee_of- 4(Person,Simone)
								-owns-> 5(Dog,Bobik)
				6(Country,Czechoslovakia)
					-contains-> 7(City,Trnava)
						-contains-> 8(Company,Tinfoil)
							<-employee_of- 9(Person,Jana)
								-citizen_of-> 6(Country,Czechoslovakia)
								-lives_in-> 7(City,Trnava)
							<-employee_of- 10(Person,Milan)
								-citizen_of-> 6(Country,Czechoslovakia)
								-lives_in-> 11(City,Bratislava)
						-contains-> 12(Company,Toasters)
							<-employee_of- 13(Person,Juraj)
								-citizen_of-> 6(Country,Czechoslovakia)
								-lives_in-> 7(City,Trnava)
							<-employee_of- 14(Person,Zuzana)
								-citizen_of-> 6(Country,Czechoslovakia)
								-lives_in-> 7(City,Trnava)
			"""
		);
	}
	
	@Test
	public void testGetAllData() {
		// TODO fix objectIdMap has dataIDs now instead of "Class: Value"
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createDataA(session, "TestNamespace", "");
			Set<String> expected = Set.of(
				"Peugeot", "Skoda", "Toyota", "Antwerp", "Bratislava", "Ghent", "Linz", "Prague", "Vienna", "Alpha", 
				"Automobiles", "Bakery", "Beta", "Gamma", "Gothic", "Lentils", "Logs", "Papermill", "Pilsner", 
				"Velocipeds", "Voleyballs", "Austria", "Belgium", "Czechoslovakia", "Adam", "Alex", "Anna", "Ben", 
				"Clara", "Ella", "Emma", "Eva", "Hugo", "Ian", "Julia", "Leo", "Lisa", "Luca", "Mark", "Max", "Mia", 
				"Nora", "Paul", "Rita", "Sara", "Simon", "Tom", "Zoe");
			String query = "CALL deposit.get.data([], '') YIELD data RETURN data";
			Result queryResult = session.run(query);
			assertQueryResult(queryResult, expected);
		}
	}

	@Test
	public void testGetOneCity1() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createDataA(session, "TestNamespace", "");
			String rootId = objectIdMap.get("8");
			List<String> rootIds = List.of(rootId);
			Set<String> expected = Set.of(
				"Alex",
				"Austria",
				"Clara",
				"Ella",
				"Emma",
				"Lentils",
				"Linz",
				"Logs"
			);
			String query = "CALL deposit.get.data($rootIds, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("rootIds", rootIds));
			assertQueryResult(queryResult, expected);
		}
	}
	
	@Test
	public void testGetOneCity2() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createDataA(session, "TestNamespace", "");
			String rootId = objectIdMap.get("14");
			List<String> rootIds = List.of(rootId);
			Set<String> expected = Set.of(
				"Belgium",
				"Ghent",
				"Gamma",
				"Gothic",
				"Hugo",
				"Ben",
				"Ian",
				"Julia",
				"Leo"
			);
			String query = "CALL deposit.get.data($rootIds, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("rootIds", rootIds));
			assertQueryResult(queryResult, expected);
		}
	}
	
	@Test
	public void testGetOneCompany() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createDataA(session, "TestNamespace", "");
			String rootId = objectIdMap.get("3");
			List<String> rootIds = List.of(rootId);
			Set<String> expected = Set.of(
				"Austria",
				"Vienna",
				"Velocipeds",
				"Adam",
				"Alex"
			);
			String query = "CALL deposit.get.data($rootIds, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("rootIds", rootIds));
			assertQueryResult(queryResult, expected);
		}
	}

	@Test
	public void testGetOnePerson() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createDataA(session, "TestNamespace", "");
			String rootId = objectIdMap.get("19");
			List<String> rootIds = List.of(rootId);
			Set<String> expected = Set.of(
				"Austria",
				"Linz",
				"Lentils",
				"Emma"
			);
			String query = "CALL deposit.get.data($rootIds, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("rootIds", rootIds));
			assertQueryResult(queryResult, expected);
		}
	}

	@Test
	public void testGetThreePersonsFromTwoCompanies() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createDataA(session, "TestNamespace", "");
			String rootId1 = objectIdMap.get("19");
			String rootId2 = objectIdMap.get("6");
			String rootId3 = objectIdMap.get("7");
			List<String> rootIds = List.of(rootId1, rootId2, rootId3);
			Set<String> expected = Set.of(
				"Austria",
				"Vienna",
				"Velocipeds",
				"Adam",
				"Alex",
				"Linz",
				"Lentils",
				"Emma"
			);
			String query = "CALL deposit.get.data($rootIds, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("rootIds", rootIds));
			assertQueryResult(queryResult, expected);
		}
	}

	@Test
	public void testGetCompanyAndPersonFromAnotherCompany() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createDataA(session, "TestNamespace", "");
			String rootId1 = objectIdMap.get("3");
			String rootId2 = objectIdMap.get("19");
			List<String> rootIds = List.of(rootId1, rootId2);
			Set<String> expected = Set.of(
				"Austria",
				"Vienna",
				"Velocipeds",
				"Linz",
				"Emma"
			);
			String query = "CALL deposit.get.data($rootIds, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("rootIds", rootIds));
			assertQueryResult(queryResult, expected);
		}
	}

	@Test
	public void testGetOneClass() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createDataA(session, "TestNamespace", "");
			String rootId = getClassId(session, "Car");
			List<String> rootIds = List.of(rootId);
			Set<String> expected = Set.of(
				"Belgium",
				"Czechoslovakia",
				"Antwerp",
				"Automobiles",
				"Mark",
				"Toyota",
				"Max",
				"Peugeot",
				"Bratislava",
				"Bakery",
				"Zoe",
				"Skoda"
			);
			String query = "CALL deposit.get.data($rootIds, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("rootIds", rootIds));
			assertQueryResult(queryResult, expected);
		}
	}

	@Test
	public void testGetOneClassAndOnePerson() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createDataA(session, "TestNamespace", "");
			String rootId1 = getClassId(session, "Car");
			String rootId2 = objectIdMap.get("20");
			List<String> rootIds = List.of(rootId1, rootId2);
			Set<String> expected = Set.of(
				"Mia",
				"Czechoslovakia",
				"Prague",
				"Toyota",
				"Peugeot",
				"Pilsner",
				"Skoda"
			);
			String query = "CALL deposit.get.data($rootIds, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("rootIds", rootIds));
			assertQueryResult(queryResult, expected);
		}
	}

	@Test
	public void testGetObjectsByData() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createDataA(session, "TestNamespace", "");
			String rootId = objectIdMap.get("14");
			Set<String> expected = Set.of(
					objectIdMap.get("13"),
					objectIdMap.get("14"),
					objectIdMap.get("5"),
					objectIdMap.get("24"),
					objectIdMap.get("9"),
					objectIdMap.get("12"),
					objectIdMap.get("25"),
					objectIdMap.get("27"),
					objectIdMap.get("28")
			);
			String query = "CALL deposit.get.data($rootIds, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("rootIds", List.of(rootId)));
			Map<String, Object> data = (Map<String, Object>) queryResult.next().get("data").asMap().get("graph");
			
			Set<String> actual = new HashSet<>();
			query = "CALL deposit.get.objectsByData($data, [], false, '') YIELD data RETURN data";
			queryResult = session.run(query, Map.of("data", data));
			if (queryResult.hasNext()) {
				Record record = queryResult.next();
				Map<String, List<String>> objectMap = record.get("data").asMap(v -> v.asList(Value::asString));
				objectMap.values().forEach(actual::addAll);
			}
			assertEquals("Unexpected objects returned", expected, actual);
		}
	}
	
	@Test
	public void testGetObjectsWithIdenticalDescriptorsByData() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createDataB(session, "TestNamespace", "");
			String rootId = objectIdMap.get("1.1");
			Set<String> expected = Set.of(
					objectIdMap.get("1"),
					objectIdMap.get("1.1"),
					objectIdMap.get("1.1.1"),
					objectIdMap.get("1.1.2"),
					objectIdMap.get("1.1.3")
			);
			String query = "CALL deposit.get.data($rootIds, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("rootIds", List.of(rootId)));
			Map<String, Object> data = (Map<String, Object>) queryResult.next().get("data").asMap().get("graph");
			
			Set<String> actual = new HashSet<>();
			query = "CALL deposit.get.objectsByData($data, [], false, '') YIELD data RETURN data";
			queryResult = session.run(query, Map.of("data", data));
			if (queryResult.hasNext()) {
				Record record = queryResult.next();
				Map<String, List<String>> objectMap = record.get("data").asMap(v -> v.asList(Value::asString));
				objectMap.values().forEach(actual::addAll);
			}

			assertEquals("Unexpected objects returned", expected, actual);
		}
	}

	@Test
	public void testGetObjectsWithWithinClassRelationshipByData() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createDataB(session, "TestNamespace", "");
			String rootId = objectIdMap.get("2.3.3");
			Set<String> expected = Set.of(
					objectIdMap.get("2"),
					objectIdMap.get("2.3"),
					objectIdMap.get("2.3.3")
			);
			String query = "CALL deposit.get.data($rootIds, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("rootIds", List.of(rootId)));
			Map<String, Object> data = (Map<String, Object>) queryResult.next().get("data").asMap().get("graph");

			Set<String> actual = new HashSet<>();
			query = "CALL deposit.get.objectsByData($data, [], false, '') YIELD data RETURN data";
			queryResult = session.run(query, Map.of("data", data));
			if (queryResult.hasNext()) {
				Record record = queryResult.next();
				Map<String, List<String>> objectMap = record.get("data").asMap(v -> v.asList(Value::asString));
				objectMap.values().forEach(actual::addAll);
			}

			assertEquals("Unexpected objects returned", expected, actual);
		}
	}

	@Test
	public void testGetObjectsByReducedData() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createDataA(session, "TestNamespace", "");
			Set<String> expected = Set.of(
					objectIdMap.get("13"),
					objectIdMap.get("14"),
					objectIdMap.get("5"),
					objectIdMap.get("24"),
					objectIdMap.get("9"),
					objectIdMap.get("12"),
					objectIdMap.get("25"),
					objectIdMap.get("27"),
					objectIdMap.get("28")
			);
			Map<String, Object> data = decodeJsonToData("""
				{
					"nodes":[
						{"properties":{},"id":16,"label":"Object"},
						{"properties":{},"id":33,"label":"Object"},
						{"properties":{},"id":48,"label":"Object"},
						{"properties":{},"id":53,"label":"Object"},
						{"properties":{},"id":55,"label":"Object"},
						{"properties":{},"id":90,"label":"Object"},
						{"properties":{},"id":92,"label":"Object"},
						{"properties":{},"id":99,"label":"Object"},
						{"properties":{},"id":104,"label":"Object"},
						{"properties":{"name":"Company"},"id":11,"label":"Class"},
						{"properties":{"name":"Person"},"id":19,"label":"Class"},
						{"properties":{"name":"Country"},"id":3,"label":"Class"},
						{"properties":{"name":"City"},"id":7,"label":"Class"},
						{"properties":{"name":"Name","value":"Gamma"},"id":17,"label":"Descriptor"},
						{"properties":{"name":"Name","value":"Hugo"},"id":34,"label":"Descriptor"},
						{"properties":{"name":"Name","value":"Ben"},"id":49,"label":"Descriptor"},
						{"properties":{"name":"Name","value":"Belgium"},"id":54,"label":"Descriptor"},
						{"properties":{"name":"Name","value":"Ghent"},"id":56,"label":"Descriptor"},
						{"properties":{"name":"Name","value":"Gothic"},"id":91,"label":"Descriptor"},
						{"properties":{"name":"Name","value":"Ian"},"id":93,"label":"Descriptor"},
						{"properties":{"name":"Name","value":"Julia"},"id":100,"label":"Descriptor"},
						{"properties":{"name":"Name","value":"Leo"},"id":105,"label":"Descriptor"}
					],
					"edges":[
						{"id":252,"outV":92,"inV":16,"type":"RELATED_TO","properties":{"label":"employee_of"}},
						{"id":249,"outV":33,"inV":16,"type":"RELATED_TO","properties":{"label":"employee_of"}},
						{"id":248,"outV":16,"inV":90,"type":"RELATED_TO","properties":{"label":"competes_with"}},
						{"id":247,"outV":55,"inV":16,"type":"RELATED_TO","properties":{"label":"contains"}},
						{"id":21,"outV":16,"inV":17,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":20,"outV":16,"inV":11,"type":"MEMBER_OF","properties":{}},
						{"id":251,"outV":33,"inV":55,"type":"RELATED_TO","properties":{"label":"lives_in"}},
						{"id":250,"outV":33,"inV":53,"type":"RELATED_TO","properties":{"label":"citizen_of"}},
						{"id":41,"outV":33,"inV":36,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":40,"outV":33,"inV":34,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":39,"outV":33,"inV":19,"type":"MEMBER_OF","properties":{}},
						{"id":228,"outV":48,"inV":55,"type":"RELATED_TO","properties":{"label":"lives_in"}},
						{"id":227,"outV":48,"inV":53,"type":"RELATED_TO","properties":{"label":"citizen_of"}},
						{"id":56,"outV":48,"inV":51,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":55,"outV":48,"inV":49,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":54,"outV":48,"inV":19,"type":"MEMBER_OF","properties":{}},
						{"id":261,"outV":104,"inV":53,"type":"RELATED_TO","properties":{"label":"citizen_of"}},
						{"id":258,"outV":99,"inV":53,"type":"RELATED_TO","properties":{"label":"citizen_of"}},
						{"id":253,"outV":92,"inV":53,"type":"RELATED_TO","properties":{"label":"citizen_of"}},
						{"id":246,"outV":53,"inV":55,"type":"RELATED_TO","properties":{"label":"contains"}},
						{"id":60,"outV":53,"inV":54,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":59,"outV":53,"inV":3,"type":"MEMBER_OF","properties":{}},
						{"id":262,"outV":104,"inV":55,"type":"RELATED_TO","properties":{"label":"lives_in"}},
						{"id":259,"outV":99,"inV":55,"type":"RELATED_TO","properties":{"label":"lives_in"}},
						{"id":255,"outV":55,"inV":90,"type":"RELATED_TO","properties":{"label":"contains"}},
						{"id":254,"outV":92,"inV":55,"type":"RELATED_TO","properties":{"label":"lives_in"}},
						{"id":63,"outV":55,"inV":56,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":62,"outV":55,"inV":7,"type":"MEMBER_OF","properties":{}},
						{"id":260,"outV":104,"inV":90,"type":"RELATED_TO","properties":{"label":"employee_of"}},
						{"id":257,"outV":99,"inV":90,"type":"RELATED_TO","properties":{"label":"employee_of"}},
						{"id":103,"outV":90,"inV":91,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":102,"outV":90,"inV":11,"type":"MEMBER_OF","properties":{}},
						{"id":107,"outV":92,"inV":95,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":106,"outV":92,"inV":93,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":105,"outV":92,"inV":19,"type":"MEMBER_OF","properties":{}},
						{"id":115,"outV":99,"inV":102,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":114,"outV":99,"inV":100,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":113,"outV":99,"inV":19,"type":"MEMBER_OF","properties":{}},
						{"id":120,"outV":104,"inV":107,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":119,"outV":104,"inV":105,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":118,"outV":104,"inV":19,"type":"MEMBER_OF","properties":{}},
						{"id":210,"outV":19,"inV":11,"type":"RELATED_TO","properties":{"label":"employee_of"}},
						{"id":206,"outV":7,"inV":11,"type":"RELATED_TO","properties":{"label":"contains"}},
						{"id":14,"outV":11,"inV":12,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":214,"outV":19,"inV":7,"type":"RELATED_TO","properties":{"label":"lives_in"}},
						{"id":212,"outV":19,"inV":3,"type":"RELATED_TO","properties":{"label":"citizen_of"}},
						{"id":27,"outV":19,"inV":23,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":25,"outV":19,"inV":20,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":204,"outV":3,"inV":7,"type":"RELATED_TO","properties":{"label":"contains"}},
						{"id":4,"outV":3,"inV":4,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":9,"outV":7,"inV":8,"type":"HAS_DESCRIPTOR","properties":{}}
					]
				}
			""");
			Set<String> actual = new HashSet<>();
			String query = "CALL deposit.get.objectsByData($data, [], false, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("data", data));
			if (queryResult.hasNext()) {
				Record record = queryResult.next();
				Map<String, List<String>> objectMap = record.get("data").asMap(v -> v.asList(Value::asString));
				objectMap.values().forEach(actual::addAll);
			}
			assertEquals("Unexpected objects returned", expected, actual);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	@Test
	public void testShouldNotReturnObjects() {
		try (Session session = driver.session()) {
			createDataA(session, "TestNamespace", "");
			// replaced Hugo with Karl
			Map<String, Object> data = decodeJsonToData("""
				{
					"nodes":[
						{"properties":{},"id":16,"label":"Object"},
						{"properties":{},"id":33,"label":"Object"},
						{"properties":{},"id":48,"label":"Object"},
						{"properties":{},"id":53,"label":"Object"},
						{"properties":{},"id":55,"label":"Object"},
						{"properties":{},"id":90,"label":"Object"},
						{"properties":{},"id":92,"label":"Object"},
						{"properties":{},"id":99,"label":"Object"},
						{"properties":{},"id":104,"label":"Object"},
						{"properties":{"name":"Company"},"id":11,"label":"Class"},
						{"properties":{"name":"Person"},"id":19,"label":"Class"},
						{"properties":{"name":"Country"},"id":3,"label":"Class"},
						{"properties":{"name":"City"},"id":7,"label":"Class"},
						{"properties":{"name":"Name","value":"Gamma"},"id":17,"label":"Descriptor"},
						{"properties":{"name":"Name","value":"Karl"},"id":34,"label":"Descriptor"},
						{"properties":{"name":"Name","value":"Ben"},"id":49,"label":"Descriptor"},
						{"properties":{"name":"Name","value":"Belgium"},"id":54,"label":"Descriptor"},
						{"properties":{"name":"Name","value":"Ghent"},"id":56,"label":"Descriptor"},
						{"properties":{"name":"Name","value":"Gothic"},"id":91,"label":"Descriptor"},
						{"properties":{"name":"Name","value":"Ian"},"id":93,"label":"Descriptor"},
						{"properties":{"name":"Name","value":"Julia"},"id":100,"label":"Descriptor"},
						{"properties":{"name":"Name","value":"Leo"},"id":105,"label":"Descriptor"}
					],
					"edges":[
						{"id":252,"outV":92,"inV":16,"type":"RELATED_TO","properties":{"label":"employee_of"}},
						{"id":249,"outV":33,"inV":16,"type":"RELATED_TO","properties":{"label":"employee_of"}},
						{"id":248,"outV":16,"inV":90,"type":"RELATED_TO","properties":{"label":"competes_with"}},
						{"id":247,"outV":55,"inV":16,"type":"RELATED_TO","properties":{"label":"contains"}},
						{"id":21,"outV":16,"inV":17,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":20,"outV":16,"inV":11,"type":"MEMBER_OF","properties":{}},
						{"id":251,"outV":33,"inV":55,"type":"RELATED_TO","properties":{"label":"lives_in"}},
						{"id":250,"outV":33,"inV":53,"type":"RELATED_TO","properties":{"label":"citizen_of"}},
						{"id":41,"outV":33,"inV":36,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":40,"outV":33,"inV":34,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":39,"outV":33,"inV":19,"type":"MEMBER_OF","properties":{}},
						{"id":228,"outV":48,"inV":55,"type":"RELATED_TO","properties":{"label":"lives_in"}},
						{"id":227,"outV":48,"inV":53,"type":"RELATED_TO","properties":{"label":"citizen_of"}},
						{"id":56,"outV":48,"inV":51,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":55,"outV":48,"inV":49,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":54,"outV":48,"inV":19,"type":"MEMBER_OF","properties":{}},
						{"id":261,"outV":104,"inV":53,"type":"RELATED_TO","properties":{"label":"citizen_of"}},
						{"id":258,"outV":99,"inV":53,"type":"RELATED_TO","properties":{"label":"citizen_of"}},
						{"id":253,"outV":92,"inV":53,"type":"RELATED_TO","properties":{"label":"citizen_of"}},
						{"id":246,"outV":53,"inV":55,"type":"RELATED_TO","properties":{"label":"contains"}},
						{"id":60,"outV":53,"inV":54,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":59,"outV":53,"inV":3,"type":"MEMBER_OF","properties":{}},
						{"id":262,"outV":104,"inV":55,"type":"RELATED_TO","properties":{"label":"lives_in"}},
						{"id":259,"outV":99,"inV":55,"type":"RELATED_TO","properties":{"label":"lives_in"}},
						{"id":255,"outV":55,"inV":90,"type":"RELATED_TO","properties":{"label":"contains"}},
						{"id":254,"outV":92,"inV":55,"type":"RELATED_TO","properties":{"label":"lives_in"}},
						{"id":63,"outV":55,"inV":56,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":62,"outV":55,"inV":7,"type":"MEMBER_OF","properties":{}},
						{"id":260,"outV":104,"inV":90,"type":"RELATED_TO","properties":{"label":"employee_of"}},
						{"id":257,"outV":99,"inV":90,"type":"RELATED_TO","properties":{"label":"employee_of"}},
						{"id":103,"outV":90,"inV":91,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":102,"outV":90,"inV":11,"type":"MEMBER_OF","properties":{}},
						{"id":107,"outV":92,"inV":95,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":106,"outV":92,"inV":93,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":105,"outV":92,"inV":19,"type":"MEMBER_OF","properties":{}},
						{"id":115,"outV":99,"inV":102,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":114,"outV":99,"inV":100,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":113,"outV":99,"inV":19,"type":"MEMBER_OF","properties":{}},
						{"id":120,"outV":104,"inV":107,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":119,"outV":104,"inV":105,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":118,"outV":104,"inV":19,"type":"MEMBER_OF","properties":{}},
						{"id":210,"outV":19,"inV":11,"type":"RELATED_TO","properties":{"label":"employee_of"}},
						{"id":206,"outV":7,"inV":11,"type":"RELATED_TO","properties":{"label":"contains"}},
						{"id":14,"outV":11,"inV":12,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":214,"outV":19,"inV":7,"type":"RELATED_TO","properties":{"label":"lives_in"}},
						{"id":212,"outV":19,"inV":3,"type":"RELATED_TO","properties":{"label":"citizen_of"}},
						{"id":27,"outV":19,"inV":23,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":25,"outV":19,"inV":20,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":204,"outV":3,"inV":7,"type":"RELATED_TO","properties":{"label":"contains"}},
						{"id":4,"outV":3,"inV":4,"type":"HAS_DESCRIPTOR","properties":{}},
						{"id":9,"outV":7,"inV":8,"type":"HAS_DESCRIPTOR","properties":{}}
					]
				}
			""");
			Set<String> actual = new HashSet<>();
			String query = "CALL deposit.get.objectsByData($data, [], false, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("data", data));
			if (queryResult.hasNext()) {
				Record record = queryResult.next();
				Map<String, List<String>> objectMap = record.get("data").asMap(v -> v.asList(Value::asString));
				objectMap.values().forEach(actual::addAll);
			}
			System.out.println(actual); // DEBUG
			assertTrue("Should not return any objects", actual.isEmpty());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	@Test
	public void testGetObjectsByExactData() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createDataA(session, "TestNamespace", "");
			Set<String> expected = Set.of(
					objectIdMap.get("13"),
					objectIdMap.get("14"),
					objectIdMap.get("5"),
					objectIdMap.get("9")
			);
			Map<String, Object> data = decodeJsonToData("""
				{"nodes":[
					{"properties":{},"id":16,"label":"Object"},
					{"properties":{},"id":33,"label":"Object"},
					{"properties":{},"id":53,"label":"Object"},
					{"properties":{},"id":55,"label":"Object"},
					{"properties":{"name":"Company"},"id":11,"label":"Class"},
					{"properties":{"name":"Person"},"id":19,"label":"Class"},
					{"properties":{"name":"Country"},"id":3,"label":"Class"},
					{"properties":{"name":"City"},"id":7,"label":"Class"},
					{"properties":{"name":"Name","value":"Gamma"},"id":17,"label":"Descriptor"},
					{"properties":{"name":"File","value":"Hugo.tmp"},"id":36,"label":"Descriptor"},
					{"properties":{"name":"Name","value":"Hugo"},"id":34,"label":"Descriptor"},
					{"properties":{"name":"Name","value":"Belgium"},"id":54,"label":"Descriptor"},
					{"properties":{"name":"Name","value":"Ghent"},"id":56,"label":"Descriptor"},
					{"properties":{"name":"Name","type":"String"},"id":12,"label":"Descriptor"}
				],
				"edges":[
					{"outV":33,"inV":16,"type":"RELATED_TO","properties":{"label":"employee_of"},"id":249},
					{"outV":55,"inV":16,"type":"RELATED_TO","properties":{"label":"contains"},"id":247},
					{"outV":16,"inV":17,"type":"HAS_DESCRIPTOR","properties":{},"id":21},
					{"outV":16,"inV":11,"type":"MEMBER_OF","properties":{},"id":20},
					{"outV":33,"inV":55,"type":"RELATED_TO","properties":{"label":"lives_in"},"id":251},
					{"outV":33,"inV":53,"type":"RELATED_TO","properties":{"label":"citizen_of"},"id":250},
					{"outV":33,"inV":36,"type":"HAS_DESCRIPTOR","properties":{},"id":41},
					{"outV":33,"inV":34,"type":"HAS_DESCRIPTOR","properties":{},"id":40},
					{"outV":33,"inV":19,"type":"MEMBER_OF","properties":{},"id":39},
					{"outV":53,"inV":55,"type":"RELATED_TO","properties":{"label":"contains"},"id":246},
					{"outV":53,"inV":54,"type":"HAS_DESCRIPTOR","properties":{},"id":60},
					{"outV":53,"inV":3,"type":"MEMBER_OF","properties":{},"id":59},
					{"outV":55,"inV":56,"type":"HAS_DESCRIPTOR","properties":{},"id":63},
					{"outV":55,"inV":7,"type":"MEMBER_OF","properties":{},"id":62},
					{"outV":19,"inV":11,"type":"RELATED_TO","properties":{"label":"employee_of"},"id":210},
					{"outV":7,"inV":11,"type":"RELATED_TO","properties":{"label":"contains"},"id":206},
					{"outV":11,"inV":12,"type":"HAS_DESCRIPTOR","properties":{},"id":14},
					{"outV":19,"inV":7,"type":"RELATED_TO","properties":{"label":"lives_in"},"id":214},
					{"outV":19,"inV":3,"type":"RELATED_TO","properties":{"label":"citizen_of"},"id":212},
					{"outV":19,"inV":23,"type":"HAS_DESCRIPTOR","properties":{},"id":27},
					{"outV":19,"inV":20,"type":"HAS_DESCRIPTOR","properties":{},"id":25},
					{"outV":3,"inV":7,"type":"RELATED_TO","properties":{"label":"contains"},"id":204},
					{"outV":3,"inV":4,"type":"HAS_DESCRIPTOR","properties":{},"id":4},
					{"outV":7,"inV":8,"type":"HAS_DESCRIPTOR","properties":{},"id":9}
				]}
			""");
			Set<String> actual = new HashSet<>();
			String query = "CALL deposit.get.objectsByData($data, [], true, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("data", data));
			if (queryResult.hasNext()) {
				Record record = queryResult.next();
				Map<String, List<String>> objectMap = record.get("data").asMap(v -> v.asList(Value::asString));
				objectMap.values().forEach(actual::addAll);
			}
			assertEquals("Unexpected objects returned", expected, actual);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	@Test
	public void testShouldNotReturnObjectsByExactData() {
		try (Session session = driver.session()) {
			createDataA(session, "TestNamespace", "");
			// Missing Hugo.tmp
			Map<String, Object> data = decodeJsonToData("""
				{"nodes":[
					{"properties":{},"id":16,"label":"Object"},
					{"properties":{},"id":33,"label":"Object"},
					{"properties":{},"id":53,"label":"Object"},
					{"properties":{},"id":55,"label":"Object"},
					{"properties":{"name":"Company"},"id":11,"label":"Class"},
					{"properties":{"name":"Person"},"id":19,"label":"Class"},
					{"properties":{"name":"Country"},"id":3,"label":"Class"},
					{"properties":{"name":"City"},"id":7,"label":"Class"},
					{"properties":{"name":"Name","value":"Gamma"},"id":17,"label":"Descriptor"},
					{"properties":{"name":"Name","value":"Hugo"},"id":34,"label":"Descriptor"},
					{"properties":{"name":"Name","value":"Belgium"},"id":54,"label":"Descriptor"},
					{"properties":{"name":"Name","value":"Ghent"},"id":56,"label":"Descriptor"},
					{"properties":{"name":"Name","type":"String"},"id":12,"label":"Descriptor"}
				],
				"edges":[
					{"outV":33,"inV":16,"type":"RELATED_TO","properties":{"label":"employee_of"},"id":249},
					{"outV":55,"inV":16,"type":"RELATED_TO","properties":{"label":"contains"},"id":247},
					{"outV":16,"inV":17,"type":"HAS_DESCRIPTOR","properties":{},"id":21},
					{"outV":16,"inV":11,"type":"MEMBER_OF","properties":{},"id":20},
					{"outV":33,"inV":55,"type":"RELATED_TO","properties":{"label":"lives_in"},"id":251},
					{"outV":33,"inV":53,"type":"RELATED_TO","properties":{"label":"citizen_of"},"id":250},
					{"outV":33,"inV":34,"type":"HAS_DESCRIPTOR","properties":{},"id":40},
					{"outV":33,"inV":19,"type":"MEMBER_OF","properties":{},"id":39},
					{"outV":53,"inV":55,"type":"RELATED_TO","properties":{"label":"contains"},"id":246},
					{"outV":53,"inV":54,"type":"HAS_DESCRIPTOR","properties":{},"id":60},
					{"outV":53,"inV":3,"type":"MEMBER_OF","properties":{},"id":59},
					{"outV":55,"inV":56,"type":"HAS_DESCRIPTOR","properties":{},"id":63},
					{"outV":55,"inV":7,"type":"MEMBER_OF","properties":{},"id":62},
					{"outV":19,"inV":11,"type":"RELATED_TO","properties":{"label":"employee_of"},"id":210},
					{"outV":7,"inV":11,"type":"RELATED_TO","properties":{"label":"contains"},"id":206},
					{"outV":11,"inV":12,"type":"HAS_DESCRIPTOR","properties":{},"id":14},
					{"outV":19,"inV":7,"type":"RELATED_TO","properties":{"label":"lives_in"},"id":214},
					{"outV":19,"inV":3,"type":"RELATED_TO","properties":{"label":"citizen_of"},"id":212},
					{"outV":19,"inV":23,"type":"HAS_DESCRIPTOR","properties":{},"id":27},
					{"outV":19,"inV":20,"type":"HAS_DESCRIPTOR","properties":{},"id":25},
					{"outV":3,"inV":7,"type":"RELATED_TO","properties":{"label":"contains"},"id":204},
					{"outV":3,"inV":4,"type":"HAS_DESCRIPTOR","properties":{},"id":4},
					{"outV":7,"inV":8,"type":"HAS_DESCRIPTOR","properties":{},"id":9}
				]}
			""");

			Set<String> actual = new HashSet<>();
			String query = "CALL deposit.get.objectsByData($data, [], true, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("data", data));
			if (queryResult.hasNext()) {
				Record record = queryResult.next();
				Map<String, List<String>> objectMap = record.get("data").asMap(v -> v.asList(Value::asString));
				objectMap.values().forEach(actual::addAll);
			}
			assertTrue("Should not return any objects", actual.isEmpty());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	@Test
	public void testGetNamespaceData() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createDataA(session, "TestNamespace", "transient");
			String query = "CALL deposit.get.namespaceData('') YIELD data RETURN data";
			Result queryResult = session.run(query);
			Set<String> foundNodeLabels = new HashSet<>();
			Set<String> foundEdgeTypes = new HashSet<>();
			Set<String> foundEdgeLabels = new HashSet<>();
			Set<String> foundClasses = new HashSet<>();
			Set<String> foundDescriptors = new HashSet<>();
			Set<String> foundValues = new HashSet<>();
			if (queryResult.hasNext()) {
				Map<String, Object> data = queryResult.next().get("data").asMap();
				Map<String, Object> graph = (Map<String, Object>) data.get("graph");
				List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
				List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");
				for (Map<String, Object> node : nodes) {
					String label = node.get("label").toString();
					foundNodeLabels.add(label);
					Map<String, Object> properties = (Map<String, Object>) node.get("properties");
					if (label.equals("Descriptor") && properties.containsKey("value")) {
						String value = properties.get("value").toString();
						foundValues.add(value);
					} else if (label.equals("Descriptor") && properties.containsKey("type")) {
						String name = properties.get("name").toString();
						String type = properties.get("type").toString();
						foundDescriptors.add(name + " (" + type + ")");
					} else if (label.equals("Class")) {
						String name = properties.get("name").toString();
						foundClasses.add(name);
					}
				}
				for (Map<String, Object> edge : edges) {
					String type = edge.get("type").toString();
					foundEdgeTypes.add(type);
					Map<String, Object> properties = (Map<String, Object>) edge.get("properties");
					if (properties.containsKey("label")) {
						String label = properties.get("label").toString();
						foundEdgeLabels.add(label);
					}
				}
			}
			Set<String> expectedNodeLabels = Set.of("DResource", "Archive", "Version", "Descriptor", "Object", "Class", "Namespace");
			Set<String> expectedEdgeTypes = Set.of("MEMBER_OF", "RELATED_TO", "NEXT", "CONTAINS", "HAS_RESOURCE", "HAS_DESCRIPTOR");
			Set<String> expectedEdgeLabels = Set.of("contains", "employee_of", "lives_in", "friend_of", "competes_with", "owns", "citizen_of");
			Set<String> expectedClasses = Set.of("Company", "Car", "Country", "City", "Person");
			Set<String> expectedDescriptors = Set.of("Name (String)", "File (DResource)");
			Set<String> expectedValues = Set.of("Linz", "Eva.tmp", "Ian.tmp", "Lisa", "Beta", "Julia.tmp", "Gamma", "Austria", "Mia.tmp", "Simon", "Antwerp", "Czechoslovakia", "Max.tmp", "Nora.tmp", "Luca.tmp", "Alex", "Rita", "Emma.tmp", "Mia", "Lisa.tmp", "Bakery", "Automobiles", "Bratislava", "Ella.tmp", "Peugeot", "Prague", "Adam", "Zoe", "Eva", "Tom", "Voleyballs", "Belgium", "Toyota", "Ian", "Sara", "Alex.tmp", "Rita.tmp", "Clara", "Max", "Lentils", "Mark", "Papermill", "Clara.tmp", "Ben.tmp", "Paul", "Ghent", "Anna.tmp", "Velocipeds", "Tom.tmp", "Vienna", "Zoe.tmp", "Paul.tmp", "Ella", "Simon.tmp", "Anna", "Adam.tmp", "Luca", "Ben", "Logs", "Gothic", "Mark.tmp", "Julia", "Sara.tmp", "Nora", "Skoda", "Hugo", "Leo", "Alpha", "Leo.tmp", "Pilsner", "Hugo.tmp", "Emma");
			assertEquals(expectedNodeLabels, foundNodeLabels);
			assertEquals(expectedEdgeTypes, foundEdgeTypes);
			assertEquals(expectedEdgeLabels, foundEdgeLabels);
			assertEquals(expectedClasses, foundClasses);
			assertEquals(expectedDescriptors, foundDescriptors);
			assertEquals(expectedValues, foundValues);
		}
	}
}
