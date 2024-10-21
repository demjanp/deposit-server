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
	
	private void performImportTest(String databaseDefinition, String importDefinition, String resultDefinition) {
		try (Session session = driver.session()) {
			createDataFromString(session, "TestNamespace", "", "", importDefinition);
			
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
			
			createDataFromString(session, "TestNamespace", "", "", databaseDefinition);
			
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
			
			Map<String, Map<String, GraphSONData.GSElement>> gsDataExpected = stringToGraphSON(resultDefinition);
			
			Map<String, GraphSONData.GSElement> gsClassesExpected = gsDataExpected.get("classes");
			Map<String, GraphSONData.GSElement> gsObjectsExpected = gsDataExpected.get("objects");
			
			Set<String> expectedClassValues = gsMapToSet(gsClassesExpected);
			Set<String> expectedObjectValues = gsMapToSet(gsObjectsExpected);
			
			assertEquals(expectedClassValues, actualClassValues);
			assertEquals(expectedObjectValues, actualObjectValues);
		}
	}
	
	@Test
	public void testSimpleImport() {
		String databaseDefinition = """
			1(Country,1)
				-rel-> 2(District,1)
					-rel-> 3(City,1)
			4(Country,2)
				-rel-> 5(District,1)
					-rel-> 6(City,2)
		""";
		String importDefinition = """
			1(Country,1)
				-rel-> 2(District,1)
					-rel-> 3(City,2)
		""";
		String resultDefinition = """
			1(Country,1)
				-rel-> 2(District,1)
					-rel-> 3(City,1)
					-rel-> 4(City,2)
			5(Country,2)
				-rel-> 6(District,1)
					-rel-> 7(City,2)
		""";
		performImportTest(databaseDefinition, importDefinition, resultDefinition);
	}

	@Test
	public void testImportTopLevel() {
		String databaseDefinition = """
			1(Country,1)
				-rel-> 2(District,1)
					-rel-> 3(City,2)
			4(Country,2)
				-rel-> 5(District,1)
					-rel-> 6(City,2)
		""";
		String importDefinition = """
			1(Country,3)
				-rel-> 2(District,1)
					-rel-> 3(City,2)
		""";
		String resultDefinition = """
			1(Country,1)
				-rel-> 2(District,1)
					-rel-> 3(City,2)
			4(Country,2)
				-rel-> 5(District,1)
					-rel-> 6(City,2)
			7(Country,3)
				-rel-> 8(District,1)
					-rel-> 9(City,2)
		""";
		performImportTest(databaseDefinition, importDefinition, resultDefinition);
	}
	
	@Test
	public void testAddNewTopLevel() {
		String databaseDefinition = """
			1(Trench,1)
				-contains-> 2(Feature,1)
					-contains-> 3(Find,1)
			4(Dating,1)
				-dates-> 3(Find,1)
			5(Trench,2)
				-contains-> 6(Feature,1)
					-contains-> 7(Find,1)
		""";
		String importDefinition = """
			1(Trench,1)
				-contains-> 2(Feature,1)
					-contains-> 3(Find,1)
			4(Dating,2)
				-dates-> 3(Find,1)
		""";
		String resultDefinition = """
			1(Trench,1)
				-contains-> 2(Feature,1)
					-contains-> 3(Find,1)
			4(Dating,1)
				-dates-> 3(Find,1)
			5(Trench,2)
				-contains-> 6(Feature,1)
					-contains-> 7(Find,1)
			8(Dating,2)
				-dates-> 3(Find,1)
		""";
		performImportTest(databaseDefinition, importDefinition, resultDefinition);
	}
	
	@Test
	public void testEverythingMatches() {
		String databaseDefinition = """
			1(A,1)
				-rel-> 2(B,1)
					-rel-> 3(C,1)
			4(A,2)
				-rel-> 5(B,1)
					-rel-> 6(C,2)
		""";
		String importDefinition = """
			1(A,1)
				-rel-> 2(B,1)
					-rel-> 3(C,1)
			4(A,2)
				-rel-> 5(B,1)
					-rel-> 6(C,2)
		""";
		String resultDefinition = """
			1(A,1)
				-rel-> 2(B,1)
					-rel-> 3(C,1)
			4(A,2)
				-rel-> 5(B,1)
					-rel-> 6(C,2)
		""";
		performImportTest(databaseDefinition, importDefinition, resultDefinition);
	}

	@Test
	public void testAmbiguousMatch() {
		String databaseDefinition = """
			1(A,1)
				-rel-> 2(B,1)
					-rel-> 3(C,1)
			4(A,2)
				-rel-> 5(B,1)
					-rel-> 6(C,2)
		""";
		String importDefinition = """
			1(B,1)
				-rel-> 2(C,3)
		""";
		String resultDefinition = """
			1(A,1)
				-rel-> 2(B,1)
					-rel-> 3(C,1)
			4(A,2)
				-rel-> 5(B,1)
					-rel-> 6(C,2)
			7(B,1)
				-rel-> 8(C,3)
		""";
		performImportTest(databaseDefinition, importDefinition, resultDefinition);
	}
}
