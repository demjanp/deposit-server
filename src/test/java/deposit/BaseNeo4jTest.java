package deposit;

import deposit.common.ServerContext;
import deposit.common.GraphSONData;

import org.bouncycastle.crypto.agreement.kdf.GSKKFDGenerator;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.After;
import org.junit.BeforeClass;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class BaseNeo4jTest {

	protected static Driver driver;

	@BeforeClass
	public static void setUpOnce() {
		driver = Neo4jTestSingleton.getDriver();
	}

	@AfterClass
	public static void tearDownOnce() {
		// Optionally, if you want to shut down the database after all suites
		// Neo4jTestSingleton.shutdownDatabase();
	}

	@Before
	public void setUp() {
		// Clear the database before each test
		try (Session session = driver.session();
			Transaction tx = session.beginTransaction()) {
			tx.run("MATCH (n) DETACH DELETE n");
			tx.commit();
		}
	}

	@After
	public void tearDown() {
		// Any per-test cleanup can go here, if necessary
	}
	
	public void createNamespace(Session session, String namespaceName, String versioningType, String token) {
		session.writeTransaction(tx -> {
			tx.run("CALL deposit.create.namespace($namespaceName, '', $versioningType, $token)",
					Map.of("namespaceName", namespaceName, "versioningType", versioningType, "token", token));
			tx.run("CALL deposit.set.activeNamespace($namespaceName, $token)",
					Map.of("namespaceName", namespaceName, "token", token));
			ServerContext.setActiveNamespace(namespaceName, token);
			tx.commit();
			return null;
		});
	}

	public String createObject(Session session, String className, String token) {
		return session.writeTransaction(tx -> {
			Result result = tx.run("CALL deposit.create.object($className, [], [], $token) YIELD node RETURN node.id AS objectId",
								   Map.of("className", className, "token", token));
			String objectId = result.single().get("objectId").asString();
			tx.commit();
			return objectId;
		});
	}

	public String createDescriptor(Session session, String objectId, String descriptorName, Object value, String token) {
		return session.writeTransaction(tx -> {
			Result result = tx.run("CALL deposit.set.descriptor($objectId, $descriptorName, $value, $token) YIELD node RETURN node.id AS descriptorId",
				   Map.of("objectId", objectId, "descriptorName", descriptorName, "value", value, "token", token));
			String descriptorId = result.single().get("descriptorId").asString();
			tx.commit();
			return descriptorId;
		});
	}
	
	public String createResourceDescriptor(Session session, String objectId, String descriptorName, Object value, String token) {
		String filename = (String) value;
		String content = "This is a test file.";
		String base64Content = Base64.getEncoder().encodeToString(content.getBytes());
		return session.writeTransaction(tx -> {
			Result result = tx.run("""
				CALL deposit.utils.upload($stream, $filename, $token) YIELD node AS resource
				WITH resource
				CALL deposit.create.descriptor($objectId, $descriptorName, resource, $token) YIELD node
				RETURN node.id AS descriptorId
				""",
				Map.of(
						"stream", base64Content,
						"filename", filename,
						"objectId", objectId,
						"descriptorName", descriptorName,
						"token", token
				)
			);
			String descriptorId = result.single().get("descriptorId").asString();
			tx.commit();
			return descriptorId;
		});
	}
	
	public String createClassDescriptor(Session session, String className, String descriptorName, String type, long order, String comment, String token) {
		return session.writeTransaction(tx -> {
			Result result = tx.run("CALL deposit.create.classDescriptor($className, $descriptorName, $type, $order, $comment, $token) YIELD node RETURN node.id AS descriptorId",
					Map.of("className", className, "descriptorName", descriptorName, "type", type, "order", order, "comment", comment, "token", token));
			String descriptorId = result.single().get("descriptorId").asString();
			tx.commit();
			return descriptorId;
		});
	}
	
	public String createRelationship(Session session, String objectId1, String label, String objectId2, Double weight, String token) {
		return session.writeTransaction(tx -> {
			Result result = tx.run("CALL deposit.create.relationship($objectId1, $objectId2, $label, $weight, $token) YIELD rel RETURN rel.id AS relationshipId",
					   Map.of("objectId1", objectId1, "objectId2", objectId2, "label", label, "weight", weight, "token", token));
			String relId = result.single().get("relationshipId").asString();
			tx.commit();
			return relId;
		});
	}

	public String createClassRelationship(Session session, String startClassName, String label, String endClassName, String comment, String token) {
		return session.writeTransaction(tx -> {
			Result result = tx.run("CALL deposit.create.classRelationship($startClassName, $endClassName, $label, $comment, $token) YIELD rel RETURN rel.id AS relationshipId",
					Map.of("startClassName", startClassName, "label", label, "endClassName", endClassName, "comment", comment, "token", token));
			String relId = result.single().get("relationshipId").asString();
			tx.commit();
			return relId;
		});
	}

	public String createUserTool(Session session, String userToolName, String data, String comment, String token) {
		return session.writeTransaction(tx -> {
			Result result = tx.run("CALL deposit.create.userTool($userToolName, $data, $comment, $token) YIELD node RETURN node.id AS userToolId",
					Map.of("userToolName", userToolName, "data", data, "comment", comment, "token", token));
			String userToolId = result.single().get("userToolId").asString();
			tx.commit();
			return userToolId;
		});
	}
	
	public Map<String, String> old_createDataFromString(Session session, String namespaceName, String versioningType, String token, String definition) {
		createNamespace(session, namespaceName, versioningType, token);
		Map<String, String> objectIdMap = new HashMap<>(); // Maps "Class: Value" to objectId
		Stack<String> objectStack = new Stack<>();
		Stack<Long> indentStack = new Stack<>();
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
					// Create Object
					String objectId = createObject(session, className, token);
					objectIdMap.put(fullObject, objectId);
					// Create Descriptor
					createDescriptor(session, objectId, "Name", value, token);
					if (className.equals("Person")) {
						// Create Resource Descriptor
						createResourceDescriptor(session, objectId, "File", value + ".tmp", token);
					}
					processedObjects.add(fullObject);
				}
			}
		}
		for (String line : lines) {
			Long currentIndent = countLeadingIndents(line);
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
				// Create Relationship
				createRelationship(session, srcId, relationshipLabel, tgtId, 0.0, token);
				objectStack.push(otherObjectPart);
				indentStack.push(currentIndent + 1);
			}
		}
		return objectIdMap;
	}
	
	public Map<String, String> createDataFromString(Session session, String namespaceName, String versioningType, String token, String definition) {
		createNamespace(session, namespaceName, versioningType, token);
		Map<String, String> objectIdMap = new HashMap<>();
		Map<Long, String> lastIdByIndent = new HashMap<>();
		lastIdByIndent.put(0L, null);
		String[] lines = definition.split("\n");
		Long minIndent = -1L;
		Pattern objPattern = Pattern.compile("(\\w+)\\((\\w+),(\\w+)\\)");
		for (String line : lines) {
			Long currentIndent = countLeadingIndents(line);
			if (minIndent < 0) {
				minIndent = currentIndent;
			}
			currentIndent = currentIndent - minIndent;
			String trimmedLine = line.trim();
			if (trimmedLine.isEmpty()) {
				continue;
			}
			String[] potentialObjects = extractObjectsFromLine(trimmedLine);
			for (String obj : potentialObjects) {
				Matcher matcher = objPattern.matcher(obj);
				if (matcher.matches()) {
					String dataId = matcher.group(1).trim();
					String className = matcher.group(2).trim();
					String value = matcher.group(3).trim();
					if (!objectIdMap.containsKey(dataId)) {
						String objectId = createObject(session, className, token);
						objectIdMap.put(dataId, objectId);
						createDescriptor(session, objectId, "Name", value, token);
						if (className.equals("Person")) {
							createResourceDescriptor(session, objectId, "File", value + ".tmp", token);
						}
					}
					lastIdByIndent.put(currentIndent, dataId);
				}
			}
			
			if (trimmedLine.contains("-")) {
				String relationshipLabel;
				String otherObjectPart;
				Pattern patternForward = Pattern.compile("-(.*?)->\\s*(.*)");
				Pattern patternReverse = Pattern.compile("<-(.*?)-\\s*(.*)");
				Matcher matcherForward = patternForward.matcher(trimmedLine);
				Matcher matcherReverse = patternReverse.matcher(trimmedLine);
				boolean isForward = true;
				if (matcherForward.matches()) {
					relationshipLabel = matcherForward.group(1).trim();
					otherObjectPart = matcherForward.group(2).trim();
				} else if (matcherReverse.matches()) {
					isForward = false;
					relationshipLabel = matcherReverse.group(1).trim();
					otherObjectPart = matcherReverse.group(2).trim();
				} else {
					continue;
				}
				String[] parts = otherObjectPart.split("\\(");
				String otherDataId = parts[0].trim();
				String parentDataId = lastIdByIndent.get(currentIndent - 1);
				String srcDataId = isForward ? parentDataId : otherDataId;
				String tgtDataId = isForward ? otherDataId : parentDataId;
				String srcId = objectIdMap.get(srcDataId);
				String tgtId = objectIdMap.get(tgtDataId);
				createRelationship(session, srcId, relationshipLabel, tgtId, 0.0, token);
			}
		}
		return objectIdMap;
	}
	
	public Map<String, Map<String, GraphSONData.GSElement>> createGraphSONFromString(String definition) {
		Map<String, GraphSONData.GSElement> gsObjects = new HashMap<>();
		Map<String, GraphSONData.GSElement> gsClasses = new HashMap<>();
		Map<String, String> objectIdMap = new HashMap<>();
		Map<Long, String> lastIdByIndent = new HashMap<>();
		lastIdByIndent.put(0L, null);
		String[] lines = definition.split("\n");
		Long minIndent = -1L;
		Pattern objPattern = Pattern.compile("(\\w+)\\((\\w+),(\\w+)\\)");
		for (String line : lines) {
			Long currentIndent = countLeadingIndents(line);
			if (minIndent < 0) {
				minIndent = currentIndent;
			}
			currentIndent = currentIndent - minIndent;
			String trimmedLine = line.trim();
			if (trimmedLine.isEmpty()) {
				continue;
			}
			String[] potentialObjects = extractObjectsFromLine(trimmedLine);
			for (String obj : potentialObjects) {
				Matcher matcher = objPattern.matcher(obj);
				if (matcher.matches()) {
					String dataId = matcher.group(1);
					String className = matcher.group(2);
					String value = matcher.group(3);
					if (!objectIdMap.containsKey(dataId)) {
						String objectId = createGSObject(gsObjects, gsClasses, className);
						objectIdMap.put(dataId, objectId);
						createGSDescriptor(gsObjects, gsClasses, objectId, "Name", value, "String");
						if (className.equals("Person")) {
							createGSResourceDescriptor(gsObjects, gsClasses, objectId, "File", value + ".tmp");
						}
					}
					lastIdByIndent.put(currentIndent, dataId);
				}
			}
			
			if (trimmedLine.contains("-")) {
				String relationshipLabel;
				String otherObjectPart;
				Pattern patternForward = Pattern.compile("-(.*?)->\\s*(.*)");
				Pattern patternReverse = Pattern.compile("<-(.*?)-\\s*(.*)");
				Matcher matcherForward = patternForward.matcher(trimmedLine);
				Matcher matcherReverse = patternReverse.matcher(trimmedLine);
				boolean isForward = true;
				if (matcherForward.matches()) {
					relationshipLabel = matcherForward.group(1).trim();
					otherObjectPart = matcherForward.group(2).trim();
				} else if (matcherReverse.matches()) {
					isForward = false;
					relationshipLabel = matcherReverse.group(1).trim();
					otherObjectPart = matcherReverse.group(2).trim();
				} else {
					continue;
				}
				String[] parts = otherObjectPart.split("\\(");
				String otherDataId = parts[0].trim();
				String parentDataId = lastIdByIndent.get(currentIndent - 1);
				String srcDataId = isForward ? parentDataId : otherDataId;
				String tgtDataId = isForward ? otherDataId : parentDataId;
				String srcObjectId = objectIdMap.get(srcDataId);
				String tgtObjectId = objectIdMap.get(tgtDataId);
				createGSRelationship(gsObjects, gsClasses, srcObjectId, relationshipLabel, tgtObjectId, 0.0);
			}
		}
		return Map.of(
				"objects", gsObjects,
				"classes", gsClasses
		);
	}
	
	public List<String> gsDataToList(Map<String, Map<String, ?>> gsData) {
		Map<String, GraphSONData.GSClass> gsClasses = (Map<String, GraphSONData.GSClass>) gsData.get("classes");
		Map<String, GraphSONData.GSObject> gsObjects = (Map<String, GraphSONData.GSObject>) gsData.get("objects");
		
		List<String> collected = new ArrayList<>();
		
		for (GraphSONData.GSClass gsClass : gsClasses.values()) {
			collected.add("Class: " + gsClass.name);
			for (GraphSONData.GSClassDescriptor gsClassDescriptor : gsClass.descriptors) {
				collected.add("ClassDescriptor: " + gsClass.name + "@" + gsClassDescriptor.name + "@" + gsClassDescriptor.type);
			}
			for (GraphSONData.GSClassRelationship gsClassRelationship : gsClass.relationships) {
				GraphSONData.GSClass gsTargetClass = gsClasses.get(gsClassRelationship.targetId);
				collected.add("ClassRelationship: " + gsClass.name + "@" + gsClassRelationship.label + "@" + gsTargetClass.name);
			}
		}
		
		for (GraphSONData.GSObject gsObject : gsObjects.values()) {
			collected.add("Object: " + gsObjectToString(gsObject, gsObjects));
		}
		
		return collected;
	}
	
	private String gsObjectToString(GraphSONData.GSObject gsObject, Map<String, GraphSONData.GSObject> gsObjects) {
		List<String> descriptors = new ArrayList<>();
		for (GraphSONData.GSDescriptor gsDescriptor : gsObject.descriptors) {
			descriptors.add(gsDescriptor.toString());
		}
		List<String> relationships = new ArrayList<>();
		for (GraphSONData.GSRelationship gsRelationship : gsObject.relationships) {
			GraphSONData.GSObject targetObject = gsObjects.get(gsRelationship.targetId);
				relationships.add("-[" + gsRelationship.label + "]-" + gsObjectToString(targetObject, gsObjects));
		}
		String toReturn = gsObject.className + "{" + String.join(", ", descriptors) + "}";
		if (!relationships.isEmpty()) {
			toReturn = toReturn + " [" + String.join(", ", relationships) + "]";
		}
		return toReturn;
	}
	
	// Helper for createDataFromString
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
	
	// Helper for createDataFromString
	private Long countLeadingIndents(String line) {
		Long indentCount = 0L;
		for (char c : line.toCharArray()) {
			if (c == '\t') {
				indentCount++;
			} else {
				break;
			}
		}
		return (Long) indentCount;
	}

	// Helper for createGraphSONFromString
	private String createGSObject(Map<String, GraphSONData.GSElement> gsObjects, Map<String, GraphSONData.GSElement> gsClasses, String className) {
		if (getGSClass(gsClasses, className) == null) {
			GraphSONData.GSClass gsClass = new GraphSONData.GSClass();
			gsClass.id = genGSDataId(gsClasses);
			gsClass.name = className;
			gsClass.order = 0L;
			gsClass.position = List.of(0.0, 0.0);
			gsClass.comment = "";
			gsClass.descriptors = new ArrayList<>();
			gsClass.relationships = new ArrayList<>();
			gsClasses.put(gsClass.id, gsClass);
		}
		GraphSONData.GSObject gsObject = new GraphSONData.GSObject();
		gsObject.id = genGSDataId(gsObjects);
		gsObject.className = className;
		gsObject.originalId = "0";
		gsObject.position = List.of(0.0, 0.0);
		gsObject.descriptors = new ArrayList<>();
		gsObject.relationships = new ArrayList<>();
		gsObjects.put(gsObject.id, gsObject);
		return gsObject.id;
	}
	
	// Helper for createGraphSONFromString
	private GraphSONData.GSDescriptor createGSDescriptor(Map<String, GraphSONData.GSElement> gsObjects, Map<String, GraphSONData.GSElement> gsClasses, String objectId, String descriptorName, Object value, String type) {
		GraphSONData.GSObject gsObject = (GraphSONData.GSObject) gsObjects.get(objectId);
		GraphSONData.GSDescriptor gsDescriptor = new GraphSONData.GSDescriptor();
		gsDescriptor.name = descriptorName;
		gsDescriptor.value = value;
		gsObject.descriptors.add(gsDescriptor);
		GraphSONData.GSClass gsClass = getGSClass(gsClasses, gsObject.className);
		if (!existsGSClassDescriptor(gsClass, descriptorName)) {
			GraphSONData.GSClassDescriptor gsClassDescriptor = new GraphSONData.GSClassDescriptor();
			gsClassDescriptor.name = descriptorName;
			gsClassDescriptor.type = type;
			gsClassDescriptor.order = 0L;
			gsClassDescriptor.comment = "";
			gsClass.descriptors.add(gsClassDescriptor);
		}
		return gsDescriptor;
	}
	
	// Helper for createGraphSONFromString
	private void createGSResourceDescriptor(Map<String, GraphSONData.GSElement> gsObjects, Map<String, GraphSONData.GSElement> gsClasses, String objectId, String descriptorName, Object value) {
		GraphSONData.GSDescriptor gsDescriptor = createGSDescriptor(gsObjects, gsClasses, objectId, descriptorName, value, "DResource");
		gsDescriptor.resource = new GraphSONData.GSDResource();
		gsDescriptor.resource.filename = (String) value;
		String baseFilename = gsDescriptor.resource.filename.substring(0, gsDescriptor.resource.filename.lastIndexOf('.'));
		String extension = gsDescriptor.resource.filename.substring(gsDescriptor.resource.filename.lastIndexOf('.'));
		gsDescriptor.resource.storedFilename = baseFilename + "-" + UUID.randomUUID() + extension;
	}
	
	// Helper for createGraphSONFromString
	private GraphSONData.GSRelationship createGSRelationship(Map<String, GraphSONData.GSElement> gsObjects, Map<String, GraphSONData.GSElement> gsClasses, String objectId1, String label, String objectId2, Double weight) {
		GraphSONData.GSObject gsObject = (GraphSONData.GSObject) gsObjects.get(objectId1);
		GraphSONData.GSRelationship gsRelationship = new GraphSONData.GSRelationship();
		gsRelationship.label = label;
		gsRelationship.targetId = objectId2;
		gsRelationship.weight = weight;
		gsObject.relationships.add(gsRelationship);
		
		String className1 = gsObject.className;
		String className2 = ((GraphSONData.GSObject) gsObjects.get(objectId2)).className;
		if (!existsGSClassRelationship(gsClasses, className1, label, className2)) {
			GraphSONData.GSClass gsClass1 = getGSClass(gsClasses, className1);
			GraphSONData.GSClass gsClass2 = getGSClass(gsClasses, className2);
			GraphSONData.GSClassRelationship gsClassRelationship = new GraphSONData.GSClassRelationship();
			gsClassRelationship.label = label;
			gsClassRelationship.targetId = gsClass2.id;
			gsClassRelationship.comment = "";
			gsClass1.relationships.add(gsClassRelationship);
		}
		return gsRelationship;
	}
	
	// Helper for createGraphSONFromString
	private String genGSDataId(Map<String, ?> data) {
		Set<String> keys = data.keySet();
		int maxKey = keys.stream()
						.mapToInt(Integer::parseInt)
						.max()
						.orElse(0);
		return String.valueOf(maxKey + 1);
	}
	
	// Helper for createGraphSONFromString
	private GraphSONData.GSClass getGSClass(Map<String, GraphSONData.GSElement> gsClasses, String className) {
		for (GraphSONData.GSElement element : gsClasses.values()) {
			GraphSONData.GSClass gsClass = (GraphSONData.GSClass) element;
			if (gsClass.name.equals(className)) {
				return gsClass;
			}
		}
		return null;
	}
	
	// Helper for createGraphSONFromString
	private boolean existsGSClassDescriptor(GraphSONData.GSClass gsClass, String descriptorName) {
		for (GraphSONData.GSClassDescriptor gsClassDescriptor : gsClass.descriptors) {
			if (gsClassDescriptor.name.equals(descriptorName)) {
				return true;
			}
		}
		return false;
	}
	
	// Helper for createGraphSONFromString
	private boolean existsGSClassRelationship(Map<String, GraphSONData.GSElement> gsClasses, String className1, String label, String className2) {
		GraphSONData.GSClass gsClass1 = getGSClass(gsClasses, className1);
		GraphSONData.GSClass gsClass2 = getGSClass(gsClasses, className2);
		for (GraphSONData.GSClassRelationship gsClassRelationship : gsClass1.relationships) {
			if (gsClassRelationship.label.equals(label) && gsClassRelationship.targetId.equals(gsClass2.id)) {
				return true;
			}
		}
		return false;
	}
}
