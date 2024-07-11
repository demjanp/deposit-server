package deposit.repository;

import deposit.core.*;
import org.neo4j.graphdb.*;
import org.neo4j.logging.Log;

import java.time.ZonedDateTime;
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
		// TODO: Implement
        return null;
    }
	
	public String getVersion() {
		// TODO: Implement
        return "";
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
		// TODO: Implement
        return null;
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
