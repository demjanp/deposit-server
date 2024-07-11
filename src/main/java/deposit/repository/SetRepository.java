package deposit.repository;

import deposit.core.*;
import org.neo4j.graphdb.*;
import org.neo4j.logging.Log;

import java.time.ZonedDateTime;

public class SetRepository {
	
	private final GraphDatabaseService db;
	private final Log log;
	
	public SetRepository(GraphDatabaseService db, Log log) {
		this.db = db;
		this.log = log;
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
	
	public Relationship setMember(Node classNode, Node objectNode) {
		// TODO: Implement
        return null;
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
