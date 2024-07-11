package deposit.service;

import deposit.repository.UnsetRepository;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.logging.Log;

public class UnsetService {

	private UnsetRepository unsetRepository;

	public UnsetService(GraphDatabaseService db, Log log) {
		this.unsetRepository = new UnsetRepository(db, log);
	}

    // Package-private setter for UnsetRepository to be used in tests
    void setUnsetRepository(UnsetRepository unsetRepository) {
        this.unsetRepository = unsetRepository;
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
