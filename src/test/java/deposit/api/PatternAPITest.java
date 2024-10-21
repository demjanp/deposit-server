package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.common.ServerContext;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

import java.util.List;
import java.util.UUID;

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
				"objects", Map.of(
					"Obj 1", Map.of(
						"name", "Obj 1",
						"className", "Class 1",
						"descriptors", List.of(
							Map.of(
								"name", "Descr 1",
								"type", "String"
							),
							Map.of(
								"name", "Descr 2",
								"type", "Integer"
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
						"descriptors", List.of(
							Map.of(
								"name", "Descr 3",
								"type", "String"
							),
							Map.of(
								"name", "Descr 4",
								"type", "DResource"
							)
						)
					)
				)
			);
			
			createNamespace(session, namespaceName, "transient", token);
			String patternId = createPattern(session, data, token);

			assertNotNull(patternId);
		}
	}
}
