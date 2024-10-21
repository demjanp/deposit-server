package deposit.repository;

import deposit.common.PatternData;
import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CreatePatternRepository extends BaseRepository {

	private static CreatePatternRepository instance;
	
	private CreatePatternRepository() {}
	
	public static synchronized CreatePatternRepository getInstance() {
		if (instance == null) {
			instance = new CreatePatternRepository();
		}
		return instance;
	}
	
	public Node createPattern(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, 
			String versioningType, PatternData.Pattern pattern) {
		
		// Create the Pattern node
		Node patternNode = createPatternNode(tx, timeStamp, username, namespaceName, versioningType, pattern);
		String patternId = patternNode.getProperty("id").toString();
		
		// Create the Object nodes
		Map<String, String> objectIdMap = new HashMap<>(); // {patternName: patternId, ...}
		Map<String, PatternData.PObject> pObjects = pattern.objects;
		for (PatternData.PObject pObject : pObjects.values()) {
			objectIdMap.put(pattern.name, createPatternObject(tx, timeStamp, username, namespaceName, versioningType, 
					patternId, pObject));
		}
		
		// Create the Pattern Relationships
		for (PatternData.PObject pObject : pObjects.values()) {
			String sourceId = objectIdMap.get(pObject.name);
			List<PatternData.PRelationship> relationships = pObject.relationships;
			for (PatternData.PRelationship relationship : relationships) {
				String targetId = objectIdMap.get(relationship.targetName);
				createPatternRelationship(tx, timeStamp, username, namespaceName, versioningType, 
						sourceId, targetId, relationship.label, relationship.comment);
			}
		}
		
		return patternNode;
	}
	
	private Node createPatternNode(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, 
			String versioningType, PatternData.Pattern pattern) {
		
		String validateQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(existing:Pattern {name: $patternName}) RETURN existing
		""";
		Map<String, Object> params;
		params = Map.of(
			"namespaceName", namespaceName,
			"patternName", pattern.name
		);
		try {
			Result validation = tx.execute(validateQuery, params);
			if (validation.hasNext()) {
				throwException("Error creating the Pattern: " + pattern.name + ". A Pattern with that name already exists.");
			}
		} catch (Exception e) {
			throwException("Error creating the Pattern: " + pattern.name, e);
		}
		
		String createQuery = """
			CREATE (updated:Pattern {
				id: randomUUID(),
				name: $patternName,
				comment: $comment,
				createdAt: $timeStamp,
				createdBy: $username
			})
			WITH updated
			MATCH (namespace:Namespace {name: $namespaceName})
			WITH namespace, updated
			MERGE (namespace)-[:CONTAINS]->(updated)
			WITH namespace, updated
		""";
		createQuery += Versioning.getElementUpdateCode("Pattern", false, versioningType);
		createQuery += "	RETURN updated";
		params = Map.of(
		"timeStamp", timeStamp,
		"username", username,
		"namespaceName", namespaceName,
		"patternName", pattern.name,
		"comment", pattern.comment
		);
		try {
			Result result = tx.execute(createQuery, params);
			if (result.hasNext()) {
				return (Node) result.next().get("updated");
			} else {
				throwException("Pattern creation failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error creating Pattern with name: " + pattern.name, e);
		}
		return null;
	}
	
	private String createPatternObject(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, 
			String versioningType, String patternId, PatternData.PObject pObject) {
		
		// Create the PatternObject node
		Map<String, Object> params = new HashMap<>();
		params.put("timeStamp", timeStamp);
		params.put("username", username);
		params.put("namespaceName", namespaceName);
		params.put("patternId", patternId);
		params.put("patternObjectName", pObject.name);
		params.put("className", pObject.className);
		params.put("root", pObject.root);
		params.put("unique", pObject.unique);
		params.put("matchEmpty", pObject.matchEmpty);
		params.put("comment", pObject.comment);
		
		String createQuery = """
			CREATE (updated:PatternObject {
				id: randomUUID(),
				name: $patternObjectName,
				class: $className,
				root: $root,
				unique: $unique,
				matchEmpty: $matchEmpty,
				comment: $comment,
				createdAt: $timeStamp,
				createdBy: $username
			})
			WITH updated
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(parent:Pattern {id: $patternId})
			WITH namespace, parent, updated
			MERGE (parent)-[:CONTAINS]->(updated)
			WITH namespace, parent, updated
		""";
		createQuery += Versioning.getElementUpdateCode("PatternObject", false, versioningType);
		createQuery += "	RETURN updated.id AS patternObjectId";
		
		String patternObjectId = null;
		try {
			Result result = tx.execute(createQuery, params);
			if (result.hasNext()) {
				patternObjectId = result.next().get("patternObjectId").toString();
			} else {
				throwException("Pattern Object creation failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error creating Pattern Object: " + pObject.name, e);
		}
		
		// Create Pattern Descriptors
		List<PatternData.PDescriptor> pDescriptors = pObject.descriptors;
		for (PatternData.PDescriptor pDescriptor : pDescriptors) {
			createPatternDescriptor(tx, timeStamp, username, namespaceName, versioningType, 
					patternObjectId, pDescriptor);
		}
		
		return patternObjectId;
	}
	
	private void createPatternDescriptor(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, 
			String versioningType, String patternObjectId, PatternData.PDescriptor pDescriptor) {
		
		Map<String, Object> params = new HashMap<>();
		params.put("timeStamp", timeStamp);
		params.put("username", username);
		params.put("namespaceName", namespaceName);
		params.put("patternObjectId", patternObjectId);
		params.put("descriptorName", pDescriptor.name);
		params.put("type", pDescriptor.type);
		params.put("comment", pDescriptor.comment);
		
		String createQuery = """
			CREATE (updated:Descriptor {
				id: randomUUID(),
				name: $descriptorName,
				type: $type,
				comment: $comment,
				createdAt: $timeStamp,
				createdBy: $username
			})
			WITH updated
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(:Pattern)-[:CONTAINS]->(parent:PatternObject {id: $patternObjectId})
			WITH namespace, parent, updated
			MERGE (parent)-[:HAS_DESCRIPTOR]->(updated)
			WITH namespace, parent, updated
		""";
		createQuery += Versioning.getElementUpdateCode("PatternDescriptor", false, versioningType);
		createQuery += "RETURN updated";
		try {
			Result result = tx.execute(createQuery, params);
			if (!result.hasNext()) {
				throwException("Pattern Descriptor creation failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error creating Pattern Descriptor with name: " + pDescriptor.name, e);
		}
	}
	
	private void createPatternRelationship(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, 
			String versioningType, String sourceId, String targetId, String label, String comment) {
		
		Map<String, Object> params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"sourceId", sourceId,
			"targetId", targetId,
			"label", label,
			"comment", comment
		);
		
		String createQuery = """
			MATCH (namespace:Namespace {name: $namespaceName}),
				(namespace)-[:CONTAINS]->(:Pattern)-[:CONTAINS]->(source:PatternObject {id: $sourceId}), 
				(namespace)-[:CONTAINS]->(:Pattern)-[:CONTAINS]->(target:PatternObject {id: $targetId})
			CREATE (source)-[updated:RELATED_TO {label: $label}]->(target)
			SET	updated.id = randomUUID(),
				updated.comment = $comment,
				updated.createdAt = $timeStamp,
				updated.createdBy = $username
			WITH namespace, source, target, updated
		""";
		createQuery += Versioning.getElementUpdateCode("PatternRelationship", false, versioningType);
		createQuery += "	RETURN updated";
		
		try {
			Result result = tx.execute(createQuery, params);
			if (!result.hasNext()) {
				throwException("Pattern Relationship creation failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error creating Pattern Relationship", e);
		}
	}
}
