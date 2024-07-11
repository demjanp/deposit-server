package deposit.service;

import deposit.repository.DeleteRepository;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.logging.Log;

public class DeleteService {

	private DeleteRepository deleteRepository;

	public DeleteService(GraphDatabaseService db, Log log) {
		this.deleteRepository = new DeleteRepository(db, log);
	}

    // Package-private setter for DeleteRepository to be used in tests
    void setDeleteRepository(DeleteRepository deleteRepository) {
        this.deleteRepository = deleteRepository;
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
