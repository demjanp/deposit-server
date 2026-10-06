package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;

import java.util.*;

import static org.junit.Assert.*;

public class PatternAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
	}

	private Map<String, String> createDataB(Session session, String namespaceName, String token) {
		Map<String, String> objectIdMap = new HashMap<>();

		// Create Class 1 and Class 2 objects with descriptors
		String obj1Id = createObject(session, "Class 1", token);
		createDescriptor(session, obj1Id, "Descr 1", "value 1", token);
		createDescriptor(session, obj1Id, "Descr 2", "value 2", token);
		objectIdMap.put("Obj 1", obj1Id);

		String obj2Id = createObject(session, "Class 2", token);
		createDescriptor(session, obj2Id, "Descr 3", "value 3", token);
		createDescriptor(session, obj2Id, "Descr 4", "value 4", token);
		objectIdMap.put("Obj 2", obj2Id);

		// Create relationship between Obj 1 and Obj 2
		createRelationship(session, obj1Id, "Rel 1", obj2Id, 0.0, token);

		return objectIdMap;
	}

	@Test
	public void shouldCreatePattern() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			
			Map<String, Object> data = Map.of(
				"name", "TestPattern",
				"comment", "test comment",
				"objects", Map.of(
					"Obj 1", Map.of(
						"name", "Obj 1",
						"className", "Class 1",
						"root", true,
						"unique", false,
						"matchEmpty", false,
						"comment", "test comment",
						"descriptors", List.of(
							Map.of(
									"name", "Descr 1",
									"type", "String",
									"comment", "test comment"
							),
							Map.of(
									"name", "Descr 2",
									"type", "Integer",
									"comment", "test comment"
							)
						),
						"relationships", List.of(
							Map.of(
									"label", "Rel 1",
									"targetName", "Obj 2",
									"comment", "test comment"
							)
						)
					),
					"Obj 2", Map.of(
						"name", "Obj 2",
						"className", "Class 2",
						"root", false,
						"unique", false,
						"matchEmpty", false,
						"comment", "test comment",
						"descriptors", List.of(
							Map.of(
									"name", "Descr 3",
									"type", "String",
									"comment", "test comment"
							),
							Map.of(
									"name", "Descr 4",
									"type", "DResource",
									"comment", "test comment"
							)
						),
						"relationships", List.of()
					)
				)
			);
			
			createNamespace(session, namespaceName, "transient", token);
			String patternId = createPattern(session, data, token);

			assertNotNull(patternId);

			String patternName = "TestPattern";
			Map<String, Object> patternData = getPattern(session, patternName, token);

			assertTrue(patternData.containsKey(patternName));

			Map<String, Object> actualData = (Map<String, Object>) patternData.get(patternName);

			assertEquals(data, actualData);

			Map<String, Object> dataUpdated = Map.of(
				"name", "TestPattern",
				"comment", "test comment",
				"objects", Map.of(
					"Obj A", Map.of(
						"name", "Obj A",
						"className", "Class A",
						"root", true,
						"unique", false,
						"matchEmpty", false,
						"comment", "test comment",
						"descriptors", List.of(
							Map.of(
									"name", "Descr A",
									"type", "String",
									"comment", "test comment"
							),
							Map.of(
									"name", "Descr B",
									"type", "Boolean",
									"comment", "test comment"
							)
						),
						"relationships", List.of(
							Map.of(
									"label", "Rel 1",
									"targetName", "Obj 2",
									"comment", "test comment"
							)
						)
					),
					"Obj 2", Map.of(
						"name", "Obj 2",
						"className", "Class 2",
						"root", false,
						"unique", false,
						"matchEmpty", false,
						"comment", "test comment",
						"descriptors", List.of(
							Map.of(
									"name", "Descr 3",
									"type", "String",
									"comment", "test comment"
							),
							Map.of(
									"name", "Descr 4",
									"type", "DResource",
									"comment", "test comment"
							)
						),
						"relationships", List.of()
					)
				)
			);

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.set.pattern($data, $token)", Map.of("data", dataUpdated, "token", token));
				tx.commit();
				return null;
			});

			patternData = getPattern(session, patternName, token);

			assertTrue(patternData.containsKey(patternName));

			actualData = (Map<String, Object>) patternData.get(patternName);

			assertEquals(dataUpdated, actualData);
		}
	}

	@Test
	public void shouldDeletePattern() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String patternName = "TestPattern";

			Map<String, Object> data = Map.of(
				"name", patternName,
				"comment", "test comment",
				"objects", Map.of(
					"Obj 1", Map.of(
						"name", "Obj 1",
						"className", "Class 1",
						"root", true,
						"unique", false,
						"matchEmpty", false,
						"comment", "test comment",
						"descriptors", List.of(
							Map.of(
									"name", "Descr 1",
									"type", "String",
									"comment", "test comment"
							),
							Map.of(
									"name", "Descr 2",
									"type", "Integer",
									"comment", "test comment"
							)
						),
						"relationships", List.of(
							Map.of(
									"label", "Rel 1",
									"targetName", "Obj 2",
									"comment", "test comment"
							)
						)
					),
					"Obj 2", Map.of(
						"name", "Obj 2",
						"className", "Class 2",
						"root", false,
						"unique", false,
						"matchEmpty", false,
						"comment", "test comment",
						"descriptors", List.of(
							Map.of(
									"name", "Descr 3",
									"type", "String",
									"comment", "test comment"
							),
							Map.of(
									"name", "Descr 4",
									"type", "DResource",
									"comment", "test comment"
							)
						),
						"relationships", List.of()
					)
				)
			);

			createNamespace(session, namespaceName, "transient", token);
			String patternId = createPattern(session, data, token);

			assertNotNull(patternId);


			session.writeTransaction(tx -> {
				tx.run("CALL deposit.delete.pattern($patternName, $token)", Map.of("patternName", patternName, "token", token));
				tx.commit();
				return null;
			});

			Map<String, Object> patternData = getPattern(session, patternName, token);

			assertTrue(patternData.isEmpty());
		}
	}
	
	@Test
	public void shouldGetObjectsByPattern() {
		try (Session session = driver.session()) {
			String namespaceName = "TestNamespace";
			String patternName = "TestPattern";

			createNamespace(session, namespaceName, "transient", token);

			// Create data in the database
			Map<String, String> objectIdMap = createDataB(session, namespaceName, token);

			Map<String, Object> patternData = Map.of(
				"name", patternName,
				"objects", Map.of(
					"Obj 1", Map.of(
						"name", "Obj 1",
						"className", "Class 1",
						"root", true,
						"unique", false,
						"matchEmpty", false,
						"descriptors", List.of(
							Map.of("name", "Descr 1", "type", "String"),
							Map.of("name", "Descr 2", "type", "String")
						),
						"relationships", List.of(
							Map.of("label", "Rel 1", "targetName", "Obj 2")
						)
					),
					"Obj 2", Map.of(
						"name", "Obj 2",
						"className", "Class 2",
						"root", false,
						"unique", false,
						"matchEmpty", false,
						"descriptors", List.of(
							Map.of("name", "Descr 3", "type", "String"),
							Map.of("name", "Descr 4", "type", "String")
						)
					)
				)
			);

			Map<String, Object> valueData = Map.of(
				"Obj 1", Map.of("Descr 1", "value 1", "Descr 2", "value 2"),
				"Obj 2", Map.of("Descr 3", "value 3", "Descr 4", "value 4")
			);

			createPattern(session, patternData, token);

			Result result = session.run("CALL deposit.get.objectsByPattern($patternName, $data, $token)",
				Map.of("patternName", patternName, "data", valueData, "token", token));

			Set<String> expected = Set.of(
				objectIdMap.get("Obj 1")
			);
			Set<String> actual = new HashSet<>();

			if (result.hasNext()) {
				Record record = result.next();
				if (!record.get("results").isNull()) {
					Map<String, List<String>> objectMap = record.get("results").asMap(v -> v.isNull() ? Collections.emptyList() : v.asList(Value::asString));
					objectMap.values().forEach(actual::addAll);
				}
			}

			assertEquals("Unexpected objects returned", expected, actual);
		}
	}
}
