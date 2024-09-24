package deposit.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import deposit.BaseNeo4jTest;
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

	private void createNamespace(Session session, String namespaceName, String versioningType) {
		session.writeTransaction(tx -> {
			tx.run("CALL deposit.create.namespace($namespaceName, '', $versioningType, '')",
					Map.of("namespaceName", namespaceName, "versioningType", versioningType));
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

	private String getClassId(Session session, String className) {
		return session.writeTransaction(tx -> {
			Result result = tx.run("CALL deposit.get.class($className, '') YIELD node RETURN node.id AS classId",
								   Map.of("className", className));
			String classId = result.single().get("classId").asString();
			tx.commit();
			return classId;
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
	
	private String createResourceDescriptor(Session session, String objectId, String descriptorName, Object value) {
		String filename = (String) value;
		String content = "This is a test file.";
		String base64Content = Base64.getEncoder().encodeToString(content.getBytes());
		return session.writeTransaction(tx -> {
			Result result = tx.run("""
				CALL deposit.utils.upload($stream, $filename, '') YIELD node AS resource
				WITH resource
				CALL deposit.create.descriptor($objectId, $descriptorName, resource, '') YIELD node
				RETURN node.id AS descriptorId
				""",
				Map.of(
					"stream", base64Content,
					"filename", filename,
					"objectId", objectId,
					"descriptorName", descriptorName
				)
			);
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

	private Map<String, String> createData(Session session, String namespaceName, String versioningType) {
		String definition = """
		Country: Austria
			-contains-> City: Vienna
				-contains-> Company: Velocipeds
					-competes_with-> Company: Voleyballs
					-competes_with-> Company: Gamma
					<-employee_of- Person: Adam
						-citizen_of-> Country: Austria
						-lives_in-> City: Vienna
						-friend_of-> Person: Alex
					<-employee_of- Person: Alex
						-citizen_of-> Country: Austria
						-lives_in-> City: Linz
						-friend_of-> Person: Hugo
						-friend_of-> Person: Lisa
				-contains-> Company: Voleyballs
					<-employee_of- Person: Anna
						-citizen_of-> Country: Austria
						-lives_in-> City: Vienna
					<-employee_of- Person: Ben
						-citizen_of-> Country: Belgium
						-lives_in-> City: Ghent
			-contains-> City: Linz
				-contains-> Company: Logs
					<-employee_of- Person: Clara
						-citizen_of-> Country: Austria
						-lives_in-> City: Linz
					<-employee_of- Person: Ella
						-citizen_of-> Country: Austria
						-lives_in-> City: Linz
				-contains-> Company: Lentils
					<-employee_of- Person: Emma
						-citizen_of-> Country: Austria
						-lives_in-> City: Linz
						-friend_of-> Person: Mia
					<-employee_of- Person: Eva
						-citizen_of-> Country: Czechoslovakia
						-lives_in-> City: Prague
						-friend_of-> Person: Emma
		Country: Belgium
			-contains-> City: Ghent
				-contains-> Company: Gamma
					-competes_with-> Company: Gothic
					<-employee_of- Person: Hugo
						-citizen_of-> Country: Belgium
						-lives_in-> City: Ghent
					<-employee_of- Person: Ian
						-citizen_of-> Country: Belgium
						-lives_in-> City: Ghent
				-contains-> Company: Gothic
					-competes_with-> Company: Beta
					<-employee_of- Person: Julia
						-citizen_of-> Country: Belgium
						-lives_in-> City: Ghent
					<-employee_of- Person: Leo
						-citizen_of-> Country: Belgium
						-lives_in-> City: Ghent
			-contains-> City: Antwerp
				-contains-> Company: Alpha
					<-employee_of- Person: Lisa
						-citizen_of-> Country: Belgium
						-lives_in-> City: Antwerp
					<-employee_of- Person: Luca
						-citizen_of-> Country: Belgium
						-lives_in-> City: Antwerp
				-contains-> Company: Automobiles
					<-employee_of- Person: Mark
						-citizen_of-> Country: Belgium
						-lives_in-> City: Antwerp
						-owns-> Car: Toyota
					<-employee_of- Person: Max
						-citizen_of-> Country: Belgium
						-lives_in-> City: Antwerp
						-owns-> Car: Peugeot
		Country: Czechoslovakia
			-contains-> City: Prague
				-contains-> Company: Pilsner
					<-employee_of- Person: Mia
						-citizen_of-> Country: Czechoslovakia
						-lives_in-> City: Prague
						-friend_of-> Person: Eva
					<-employee_of- Person: Nora
						-citizen_of-> Country: Czechoslovakia
						-lives_in-> City: Prague
				-contains-> Company: Papermill
					<-employee_of- Person: Paul
						-citizen_of-> Country: Czechoslovakia
						-lives_in-> City: Prague
					<-employee_of- Person: Rita
						-citizen_of-> Country: Czechoslovakia
						-lives_in-> City: Prague
			-contains-> City: Bratislava
				-contains-> Company: Beta
					<-employee_of- Person: Sara
						-citizen_of-> Country: Czechoslovakia
						-lives_in-> City: Vienna
					<-employee_of- Person: Simon
						-citizen_of-> Country: Czechoslovakia
						-lives_in-> City: Bratislava
				-contains-> Company: Bakery
					<-employee_of- Person: Tom
						-citizen_of-> Country: Czechoslovakia
						-lives_in-> City: Bratislava
					<-employee_of- Person: Zoe
						-citizen_of-> Country: Czechoslovakia
						-lives_in-> City: Bratislava
						-owns-> Car: Skoda
		""";
		createNamespace(session, namespaceName, versioningType);
		Map<String, String> objectIdMap = new HashMap<>(); // Maps "Class: Value" to objectId
		Stack<String> objectStack = new Stack<>();
		Stack<Integer> indentStack = new Stack<>();
		String[] lines = definition.split("\n");
		Set<String> processedObjects = new HashSet<>();
		for (String line : lines) {
			String trimmedLine = line.trim();
			if (trimmedLine.isEmpty()) {
				continue;
			}
			String[] potentialObjects = extractObjectsFromLine(trimmedLine);
			for (String obj : potentialObjects) {
				if (!processedObjects.contains(obj)) {
					String[] parts = obj.split(": ");
					if (parts.length < 2) continue; // Skip malformed entries
					String className = parts[0].trim();
					String value = parts[1].trim();
					String fullObject = className + ": " + value;
					String objectId = createObject(session, className);
					objectIdMap.put(fullObject, objectId);
					createDescriptor(session, objectId, "Name", value);
					if (className.equals("Person")) {
						createResourceDescriptor(session, objectId, "File", value + ".tmp");
					}
					processedObjects.add(fullObject);
				}
			}
		}
		for (String line : lines) {
			int currentIndent = countLeadingIndents(line);
			String trimmedLine = line.trim();
			while (indentStack.size() > currentIndent) {
				objectStack.pop();
				indentStack.pop();
			}
			if (!trimmedLine.contains("-")) {  // Class name with descriptor value
				String[] parts = trimmedLine.split(": ");
				if (parts.length < 2) continue; // Skip malformed lines
				String className = parts[0].trim();
				String value = parts[1].trim();
				String fullObject = className + ": " + value;
				objectStack.push(fullObject);
				indentStack.push(currentIndent);
			} else {
				String relationshipLabel;
				String otherObjectPart;
				String sourceObject;
				String targetObject;
				Pattern patternForward = Pattern.compile("-(.*?)->\\s*(.*)");
				Pattern patternReverse = Pattern.compile("<-(.*?)-\\s*(.*)");
				Matcher matcherForward = patternForward.matcher(trimmedLine);
				Matcher matcherReverse = patternReverse.matcher(trimmedLine);
				if (matcherForward.matches()) {
					relationshipLabel = matcherForward.group(1).trim();
					otherObjectPart = matcherForward.group(2).trim();
					if (objectStack.isEmpty()) {
						throw new IllegalArgumentException("No source object found for line: " + line);
					}
					sourceObject = objectStack.peek();
					targetObject = otherObjectPart;
				} else if (matcherReverse.matches()) {
					relationshipLabel = matcherReverse.group(1).trim();
					otherObjectPart = matcherReverse.group(2).trim();
					if (objectStack.isEmpty()) {
						throw new IllegalArgumentException("No target object found for line: " + line);
					}
					sourceObject = otherObjectPart;
					targetObject = objectStack.peek();
				} else {
					continue;
				}
				if (!objectIdMap.containsKey(sourceObject)) {
					throw new IllegalArgumentException("Object not found: " + sourceObject);
				}

				if (!objectIdMap.containsKey(targetObject)) {
					throw new IllegalArgumentException("Object not found: " + targetObject);
				}
				String srcId = objectIdMap.get(sourceObject);
				String tgtId = objectIdMap.get(targetObject);
				createRelationship(session, srcId, relationshipLabel, tgtId);
				objectStack.push(otherObjectPart);
				indentStack.push(currentIndent + 1);
			}
		}
		return objectIdMap;
	}

	// Helper for createData
	private String[] extractObjectsFromLine(String line) {
		List<String> objects = new ArrayList<>();
		if (!line.contains("-")) {
			objects.add(line.trim());
		} else {
			Pattern pattern = Pattern.compile("(?:-+|<-)\\s*(.*?)\\s*(?:->|-)\\s*(.*)");
			Matcher matcher = pattern.matcher(line.trim());
			if (matcher.matches()) {
				String label = matcher.group(1).trim();
				String target = matcher.group(2).trim();
				objects.add(target);
				if (label.contains(":")) {
					objects.add(label);
				}
			}
		}
		return objects.toArray(new String[0]);
	}

	// Helper for createData
	private int countLeadingIndents(String line) {
		int indentCount = 0;
		for (char c : line.toCharArray()) {
			if (c == '\t') {
				indentCount++;
			} else if (c == ' ') {
				indentCount += 0.25;
			} else {
				break;
			}
		}
		return (int) indentCount;
	}

	private static Map<String, Object> decodeJsonToData(String jsonData) throws IOException {
		ObjectMapper objectMapper = new ObjectMapper();
		return objectMapper.readValue(jsonData, new TypeReference<Map<String, Object>>() {});
	}

	@Test
	public void testGetAllData() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createData(session, "TestNamespace", "");
			Set<String> expected = new HashSet<>();
			for (String key : objectIdMap.keySet()) {
				String[] parts = key.split(": ");
				expected.add(parts[1]);
			}
			String query = "CALL deposit.get.data([], '') YIELD data RETURN data";
			Result queryResult = session.run(query);
			assertQueryResult(queryResult, expected);
		}
	}

	@Test
	public void testGetOneCity1() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createData(session, "TestNamespace", "");
			String rootId = objectIdMap.get("City: Linz");
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
			Map<String, String> objectIdMap = createData(session, "TestNamespace", "");
			String rootId = objectIdMap.get("City: Ghent");
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
			Map<String, String> objectIdMap = createData(session, "TestNamespace", "");
			String rootId = objectIdMap.get("Company: Velocipeds");
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
			Map<String, String> objectIdMap = createData(session, "TestNamespace", "");
			String rootId = objectIdMap.get("Person: Emma");
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
			Map<String, String> objectIdMap = createData(session, "TestNamespace", "");
			String rootId1 = objectIdMap.get("Person: Emma");
			String rootId2 = objectIdMap.get("Person: Adam");
			String rootId3 = objectIdMap.get("Person: Alex");
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
			Map<String, String> objectIdMap = createData(session, "TestNamespace", "");
			String rootId1 = objectIdMap.get("Company: Velocipeds");
			String rootId2 = objectIdMap.get("Person: Emma");
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
			Map<String, String> objectIdMap = createData(session, "TestNamespace", "");
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
			Map<String, String> objectIdMap = createData(session, "TestNamespace", "");
			String rootId1 = getClassId(session, "Car");
			String rootId2 = objectIdMap.get("Person: Mia");
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
			Map<String, String> objectIdMap = createData(session, "TestNamespace", "");
			String rootId = objectIdMap.get("City: Ghent");
			Set<String> expected = Set.of(
					objectIdMap.get("Country: Belgium"),
					objectIdMap.get("City: Ghent"),
					objectIdMap.get("Company: Gamma"),
					objectIdMap.get("Company: Gothic"),
					objectIdMap.get("Person: Hugo"),
					objectIdMap.get("Person: Ben"),
					objectIdMap.get("Person: Ian"),
					objectIdMap.get("Person: Julia"),
					objectIdMap.get("Person: Leo")
			);
			String query = "CALL deposit.get.data($rootIds, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("rootIds", List.of(rootId)));
			Map<String, Object> data = (Map<String, Object>) queryResult.next().get("data").asMap().get("graph");
			
			Set<String> actual = new HashSet<>();
			query = "CALL deposit.get.objectsByData($data, false, '') YIELD data RETURN data";
			queryResult = session.run(query, Map.of("data", data));
			if (queryResult.hasNext()) {
				Record record = queryResult.next();
				Map<String, String> objectMap = record.get("data").asMap(Value::asString);
				actual.addAll(objectMap.values());
			}
			assertEquals("Unexpected objects returned", expected, actual);
		}
	}

	@Test
	public void testGetObjectsByReducedData() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createData(session, "TestNamespace", "");
			Set<String> expected = Set.of(
					objectIdMap.get("Country: Belgium"),
					objectIdMap.get("City: Ghent"),
					objectIdMap.get("Company: Gamma"),
					objectIdMap.get("Company: Gothic"),
					objectIdMap.get("Person: Hugo"),
					objectIdMap.get("Person: Ben"),
					objectIdMap.get("Person: Ian"),
					objectIdMap.get("Person: Julia"),
					objectIdMap.get("Person: Leo")
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
			String query = "CALL deposit.get.objectsByData($data, false, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("data", data));
			if (queryResult.hasNext()) {
				Record record = queryResult.next();
				Map<String, String> objectMap = record.get("data").asMap(Value::asString);
				actual.addAll(objectMap.values());
			}
			assertEquals("Unexpected objects returned", expected, actual);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	public void testShouldNotReturnObjects() {
		try (Session session = driver.session()) {
			createData(session, "TestNamespace", "");
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
			String query = "CALL deposit.get.objectsByData($data, false, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("data", data));
			if (queryResult.hasNext()) {
				Record record = queryResult.next();
				Map<String, String> objectMap = record.get("data").asMap(Value::asString);
				actual.addAll(objectMap.values());
			}
			assertTrue("Should not return any objects", actual.isEmpty());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	public void testGetObjectsByExactData() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createData(session, "TestNamespace", "");
			Set<String> expected = Set.of(
					objectIdMap.get("Country: Belgium"),
					objectIdMap.get("City: Ghent"),
					objectIdMap.get("Company: Gamma"),
					objectIdMap.get("Person: Hugo")
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
			String query = "CALL deposit.get.objectsByData($data, true, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("data", data));
			if (queryResult.hasNext()) {
				Record record = queryResult.next();
				Map<String, String> objectMap = record.get("data").asMap(Value::asString);
				actual.addAll(objectMap.values());
			}
			assertEquals("Unexpected objects returned", expected, actual);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	public void testShouldNotReturnObjectsByExactData() {
		try (Session session = driver.session()) {
			createData(session, "TestNamespace", "");
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
			String query = "CALL deposit.get.objectsByData($data, true, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("data", data));
			if (queryResult.hasNext()) {
				Record record = queryResult.next();
				Map<String, String> objectMap = record.get("data").asMap(Value::asString);
				actual.addAll(objectMap.values());
			}
			assertTrue("Should not return any objects", actual.isEmpty());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	public void testGetNamespaceData() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createData(session, "TestNamespace", "transient");
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
