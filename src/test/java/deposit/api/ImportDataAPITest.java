package deposit.api;

import deposit.BaseNeo4jTest;

import deposit.common.GraphSONData;

import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;

import java.util.*;

import static org.junit.Assert.*;

public class ImportDataAPITest extends BaseNeo4jTest {

	private Map<String, Map<String, GraphSONData.GSElement>> sessionToGSData(Session session) {
		Result queryResult = session.run("CALL deposit.get.data([], '') YIELD data RETURN data");
		if (queryResult.hasNext()) {
			Map<String, Object> data = queryResult.next().get("data").asMap();
			return GraphSONData.parseData(data);
		}
		return null;
	}
	
	private void printGSMap(Map<String, GraphSONData.GSElement> gsMap) {
		gsMap.keySet().stream()
			 .sorted(Comparator.comparingInt(Integer::parseInt))
			 .forEach(key -> System.out.println(gsMap.get(key).toValues(gsMap)));
	}

	private Set<String> gsMapToSet(Map<String, GraphSONData.GSElement> gsMap) {
		Set<String> result = new HashSet<>();
		for (GraphSONData.GSElement element : gsMap.values()) {
			result.add(element.toValues(gsMap));
		}
		return result;
	}
	
	@Test
	public void testSimpleImportData() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMapImport = createDataFromString(session, "TestNamespace", "", "", """
				1(A,1)
					-rel-> 2(B,1)
						-rel-> 3(C,2)
			""");

			Map<String, Object> dataImport = session.writeTransaction(tx -> {
				Result result = tx.run("CALL deposit.get.data([], '') YIELD data RETURN data");
				assertTrue(result.hasNext());
				Map<String, Object> resultData = result.next().get("data").asMap();
				tx.commit();
				return resultData;
			});

			session.writeTransaction(tx -> {
				tx.run("MATCH (n) DETACH DELETE n");
				tx.commit();
				return null;
			});
			
			Map<String, String> objectIdMapDB = createDataFromString(session, "TestNamespace", "", "", """
				1(A,1)
					-rel-> 2(B,1)
						-rel-> 3(C,1)
				4(A,2)
					-rel-> 5(B,1)
						-rel-> 6(C,2)
			""");

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.import.data($data, [], [], false, '')", Map.of("data", dataImport));
				tx.commit();
				return null;
			});

			Map<String, Map<String, GraphSONData.GSElement>> gsDataActual = sessionToGSData(session);

			Map<String, GraphSONData.GSElement> gsClassesActual = gsDataActual.get("classes");
			Map<String, GraphSONData.GSElement> gsObjectsActual = gsDataActual.get("objects");

			Set<String> actualClassValues = gsMapToSet(gsClassesActual);
			Set<String> actualObjectValues = gsMapToSet(gsObjectsActual);

			System.out.println(); // DEBUG
			System.out.println("--- ACTUAL ---"); // DEBUG
			System.out.println(" -- Classes --"); // DEBUG
//			printGSMap(gsClassesActual);
			actualClassValues.stream().sorted().forEach(
					value -> System.out.println(value)
			);
			System.out.println("\n -- Objects --"); // DEBUG
//			printGSMap(gsObjectsActual);
			actualObjectValues.stream().sorted().forEach(
					value -> System.out.println(value)
			);
			System.out.println(); // DEBUG

			Map<String, Map<String, GraphSONData.GSElement>> gsDataExpected = createGraphSONFromString("""
				1(A,1)
					-rel-> 2(B,1)
						-rel-> 3(C,1)
						-rel-> 4(C,2)
				5(A,2)
					-rel-> 6(B,1)
						-rel-> 7(C,2)
			""");

			Map<String, GraphSONData.GSElement> gsClassesExpected = gsDataExpected.get("classes");
			Map<String, GraphSONData.GSElement> gsObjectsExpected = gsDataExpected.get("objects");

			Set<String> expectedClassValues = gsMapToSet(gsClassesExpected);
			Set<String> expectedObjectValues = gsMapToSet(gsObjectsExpected);

			System.out.println(); // DEBUG
			System.out.println("--- EXPECTED ---"); // DEBUG
			System.out.println(" -- Classes --"); // DEBUG
//			printGSMap(gsClassesExpected);
			expectedClassValues.stream().sorted().forEach(
					value -> System.out.println(value)
			);
			System.out.println("\n -- Objects --"); // DEBUG
//			printGSMap(gsObjectsExpected);
			expectedObjectValues.stream().sorted().forEach(
					value -> System.out.println(value)
			);
			System.out.println(); // DEBUG

			assertEquals(expectedClassValues, actualClassValues);
			assertEquals(expectedObjectValues, actualObjectValues);
		}
	}

	/* TODO test:
	 * import UserTools
	 * unique
	 * overwrite
	 * error on ambiguous matches
	 * Classes, ClassDescriptors & ClassRelationships not specified in data
	 */

}
