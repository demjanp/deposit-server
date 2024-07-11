package deposit.service;

import deposit.repository.SetRepository;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.logging.Log;

import java.time.ZonedDateTime;

public class SetService {

	private SetRepository setRepository;

	public SetService(GraphDatabaseService db, Log log) {
		this.setRepository = new SetRepository(db, log);
	}

    // Package-private setter for SetRepository to be used in tests
    void setSetRepository(SetRepository setRepository) {
        this.setRepository = setRepository;
    }
	
	public void setDatabase(String databaseName) {
		// TODO: Implement
	}
	
	public void setDatabaseDescription(String databaseName) {
		// TODO: Implement
	}
	
	public void setVersioningType(String versioningType) {
		// TODO: Implement
	}
	
	public void setVersionTag(String tag, ZonedDateTime timeStamp) {
		// TODO: Implement
	}
	
	public void setMember(Node classNode, Node objectNode) {
		// TODO: Implement
	}
	
	public void setClassComment(Node classNode, String comment) {
		// TODO: Implement
	}
	
	public void setClassDescriptorComment(Node classNode, String name, String comment) {
		// TODO: Implement
	}
	
	public void setPosition(Node node, double x, double y) {
		// TODO: Implement
	}
	
	public void setSwitchClassOrder(Node srcClassNode, Node tgtClassNode) {
		// TODO: Implement
	}
	
	public void setSwitchDescriptorOrder(Node classNode, String name1, String name2) {
		// TODO: Implement
	}
}
