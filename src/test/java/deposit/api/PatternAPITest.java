package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class PatternAPITest extends BaseNeo4jTest {

	private String token;

	@Before
	public void setUp() {
		super.setUp();
		token = ServerContext.getInstance().generateToken("testUser");
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
							Map.of(
									"name", "Descr 1",
									"type", "String"
							),
							Map.of(
									"name", "Descr 2",
									"type", "String"
							)
						),
						"relationships", List.of(
							Map.of(
									"label", "Rel 1",
									"targetName", "Obj 2"
							)
						)
					),
					"Obj 2", Map.of(
						"name", "Obj 2",
						"className", "Class 2",
						"root", false,
						"unique", false,
						"matchEmpty", false,
						"descriptors", List.of(
							Map.of(
									"name", "Descr 3",
									"type", "String"
							),
							Map.of(
									"name", "Descr 4",
									"type", "String"
							)
						)
					)
				)
			);

			Map<String, Object> valueData = Map.of(
				"Obj 1", Map.of(
						"Descr 1", "value 1",
						"Descr 2", "value 2"
					),
					"Obj 2", Map.of(
							"Descr 3", "value 3",
							"Descr 4", "value 4"
					)
			);

			createNamespace(session, namespaceName, "transient", token);
			createPattern(session, patternData, token);

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.get.objectsByPattern($patternName, $data, $token)", Map.of("patternName", patternName, "data", valueData, "token", token));
				tx.commit();
				return null;
			});

			assertTrue(false);
		}
	}
}
