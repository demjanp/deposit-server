package deposit.repository;

import deposit.core.*;
import org.neo4j.graphdb.*;
import org.neo4j.logging.Log;

public class DeleteRepository {
	
	private final GraphDatabaseService db;
	private final Log log;
	
	public DeleteRepository(GraphDatabaseService db, Log log) {
		this.db = db;
		this.log = log;
	}
	
	public void deleteDatabase(String databaseName) {
		// TODO: Implement
	}
	
	public void deleteClass(Node classNode) {
		// TODO: Implement
	}
	
	public void deleteObject(Node objectNode) {
		// TODO: Implement
	}
	
	public void deleteRelationship(Node srcObjectNode, Node tgtObjectNode, String label) {
		// TODO: Implement
	}
	
	public void deleteClassRelationship(Node srcClassNode, Node tgtClassNode, String label) {
		// TODO: Implement
	}
	
	public void deleteUserTool(String name) {
		// TODO: Implement
	}
	
	public void deleteAll() {
		// TODO: Implement
	}
}
