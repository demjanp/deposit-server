package deposit.repository;

import deposit.core.*;
import org.neo4j.graphdb.*;
import org.neo4j.logging.Log;

public class UnsetRepository {
	
	private final GraphDatabaseService db;
	private final Log log;
	
	public UnsetRepository(GraphDatabaseService db, Log log) {
		this.db = db;
		this.log = log;
	}
	
	public void unsetMember(Node classNode, Node objectNode) {
		// TODO: Implement
	}
	
	public void unsetDescriptor(Node objectNode, String name) {
		// TODO: Implement
	}
	
	public void unsetClassDescriptor(Node classNode, String name) {
		// TODO: Implement
	}
}
