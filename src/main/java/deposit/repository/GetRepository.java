package deposit.repository;

import deposit.core.*;
import org.neo4j.graphdb.*;
import org.neo4j.logging.Log;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class GetRepository {
	
	private final GraphDatabaseService db;
	private final Log log;
	
	public GetRepository(GraphDatabaseService db, Log log) {
		this.db = db;
		this.log = log;
	}

	public List<String> getDatabaseNames() {
		List<String> databaseNames = new ArrayList<>();
		try (Transaction tx = db.beginTx()) {
			Result result = tx.execute("CALL dbms.databases()");
			while (result.hasNext()) {
				Map<String, Object> row = result.next();
				String dbName = (String) row.get("name");
				if (!dbName.endsWith("_archive")) {
					tx.execute("USE `" + dbName + "`");
					Result metadataResult = tx.execute("MATCH (m:Metadata) RETURN m");
					if (metadataResult.hasNext()) {
						Map<String, Object> metadata = (Map<String, Object>) metadataResult.next().get("m");
						if (metadata.containsKey("depositVersion") &&
							metadata.containsKey("versioningType") &&
							metadata.containsKey("description") &&
							metadata.containsKey("createdAt") &&
							metadata.containsKey("createdBy") &&
							metadata.containsKey("updatedAt") &&
							metadata.containsKey("updatedBy") &&
							metadata.containsKey("restored")) {
							databaseNames.add(dbName);
						}
					}
				}
			}
			tx.commit();
		} catch (Exception e) {
			log.error("Error getting database names", e);
			throw new RuntimeException("Failed to get database names: " + e.getMessage(), e);
		}
		return databaseNames;
	}

	public String getVersioningType() {
		// TODO: Implement
		return "";
	}
	
	public String getCreatedBy() {
		// TODO: Implement
		return "";
	}
	
	public ZonedDateTime getCreatedAt() {
		// TODO: Implement
		return null;
	}
	
	public ZonedDateTime getUpdatedAt() {
		// TODO: Implement
		return null;
	}
	
	public String getDatabaseDescription() {
		// TODO: Implement
		return "";
	}

	public List<Node> getClass(String name) {
		List<Node> classNodes = new ArrayList<>();
		try (Transaction tx = db.beginTx()) {
			String query;
			Map<String, Object> params;
			if (name != null && !name.isEmpty()) {
				query = "MATCH (a:Class {name: $name}) RETURN a";
				params = Map.of("name", name);
			} else {
				query = "MATCH (a:Class) RETURN a";
				params = Map.of();
			}
			Result result = tx.execute(query, params);
			while (result.hasNext()) {
				classNodes.add((Node) result.next().get("a"));
			}
			tx.commit();
		} catch (Exception e) {
			log.error("Error getting Class nodes", e);
			throw new RuntimeException("Failed to get Class nodes: " + e.getMessage(), e);
		}
		return classNodes;
	}
	
	public List<Node> getObjectClasses(Node objectNode) {
		// TODO: Implement
		return null;
	}
	
	public Map<String, Map<String, Object>> getSubTree(List<Node> roots) {
		// TODO: Implement
		return null;
	}
	
	public List<Node> getObject(String uuid, List<String> classes, List<Object[]> descriptors, Map<String, Map<String, Object>> subtree) {
		// TODO: Implement
		return Collections.emptyList();
	}
	
	public List<Object[]> getDescriptor(Node objectNode, String name) {
		// TODO: Implement
		return null;
	}
	
	public List<String[]> getClassDescriptor(Node classNode, String name) {
		// TODO: Implement
		return null;
	}
	
	public List<Double> getPosition(Node node) {
		// TODO: Implement
		return null;
	}
	
	public List<Object[]> getRelationship(Node srcObjectNode, Node tgtObjectNode) {
		// TODO: Implement
		return null;
	}
	
	public List<Object[]> getNeighbors(Node objectNode) {
		// TODO: Implement
		return null;
	}
	
	public List<String[]> getClassRelationship(Node srcClassNode, Node tgtClassNode, String label) {
		// TODO: Implement
		return null;
	}
	
	public List<Object[]> getClassNeighbors(Node classNode) {
		// TODO: Implement
		return null;
	}
	
	public Map<String, List<Double>> getLayout(List<Node> elements) {
		// TODO: Implement
		return null;
	}
	
	public List<Object[]> getUserTool(String name) {
		// TODO: Implement
		return null;
	}
}
