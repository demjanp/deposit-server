package deposit.api;

import deposit.BaseNeo4jTest;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;

import java.util.*;

import static org.junit.Assert.*;

public class ImportDataAPITestOld extends BaseNeo4jTest {

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

	// Country-City-Company-Person
	private Map<String, String> createDataA(Session session, String namespaceName, String versioningType) {
		return createDataFromString(session, namespaceName, versioningType, "", """
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
			"""
		);
	}

	// Country-City-Company-Person
	private Map<String, String> createDataC(Session session, String namespaceName, String versioningType) {
		return createDataFromString(session, namespaceName, versioningType, "", """
			Country: Belgium
				-contains-> City: Antwerp
				 	-contains-> Company: Alpha
				 		<-employee_of- Person: Simone
				 			-owns-> Dog: Bobik
			Country: Czechoslovakia
				-contains-> City: Trnava
					-contains-> Company: Tinfoil
						<-employee_of- Person: Jana
							-citizen_of-> Country: Czechoslovakia
							-lives_in-> City: Trnava
						<-employee_of- Person: Milan
							-citizen_of-> Country: Czechoslovakia
							-lives_in-> City: Bratislava
					-contains-> Company: Toasters
						<-employee_of- Person: Juraj
							-citizen_of-> Country: Czechoslovakia
							-lives_in-> City: Trnava
						<-employee_of- Person: Zuzana
							-citizen_of-> Country: Czechoslovakia
							-lives_in-> City: Trnava
			"""
		);
	}
	
	@Test
	public void testSimpleImportData() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMapB = createDataC(session, "TestNamespace", "");

			Map<String, Object> dataB = session.writeTransaction(tx -> {
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
			
			Map<String, String> objectIdMapA = createDataA(session, "TestNamespace", "");

			session.writeTransaction(tx -> {
				tx.run("CALL deposit.import.data($data, [], [], false, '')", Map.of("data", dataB));
				tx.commit();
				return null;
			});

			Set<String> expected = new HashSet<>();
			for (String key : objectIdMapB.keySet()) {
				String[] parts = key.split(": ");
				expected.add(parts[1]);
			}
			for (String key : objectIdMapA.keySet()) {
				String[] parts = key.split(": ");
				expected.add(parts[1]);
			}

			String query = "CALL deposit.get.data([], '') YIELD data RETURN data";
			Result queryResult = session.run(query);
			assertQueryResult(queryResult, expected);

			// TODO test if Relationships have been created

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
