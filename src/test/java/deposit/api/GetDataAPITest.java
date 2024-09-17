package deposit.api;

import deposit.BaseNeo4jTest;
import deposit.CypherScriptExporter;
import deposit.common.ServerContext;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.neo4j.driver.Record;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class GetDataAPITest extends BaseNeo4jTest {

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

	private Map<String, String> createData(Session session, String namespaceName) {
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
					<-employee_of- Person: Max
						-citizen_of-> Country: Belgium
						-lives_in-> City: Antwerp
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
		""";
		createNamespace(session, namespaceName);
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

	@Test
	public void testGetAllData() {
		try (Session session = driver.session()) {
			Map<String, String> objectIdMap = createData(session, "TestNamespace");

			String objId_Emma = objectIdMap.get("Person: Emma"); // Linz, Lentils
			String objId_Adam = objectIdMap.get("Person: Adam"); // Vienna, Velocipeds
			String objId_Alex = objectIdMap.get("Person: Alex"); // Vienna, Velocipeds
			String objId_Linz = objectIdMap.get("City: Linz");
			String objId_Velocipeds = objectIdMap.get("Company: Velocipeds");

			/* TODO
			 * Linz
			 * Velocipeds
			 * Emma
			 * Emma, Adam, Alex
			 * Velocipeds, Emma
			 */

			List<String> rootIds = List.of(objId_Linz);

			CypherScriptExporter exporter = new CypherScriptExporter(driver);
			exporter.exportData();
			System.out.println("");
			System.out.println("Emma:" + objId_Emma);
			System.out.println("Adam:" + objId_Adam);
			System.out.println("Alex:" + objId_Alex);
			System.out.println("Linz:" + objId_Linz);
			System.out.println("Velocipeds:" + objId_Velocipeds);
			System.out.println("");

			String query = "CALL deposit.get.data($rootIds, '') YIELD data RETURN data";
			Result queryResult = session.run(query, Map.of("rootIds", rootIds));

			printQueryResult(queryResult);
		}
	}

}
