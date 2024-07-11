package deposit.repository;

import deposit.core.*;
import org.neo4j.graphdb.*;
import org.neo4j.logging.Log;

import java.time.ZonedDateTime;

public class VersioningRepository {
	
	private final GraphDatabaseService db;
	private final Log log;
	
	public VersioningRepository(GraphDatabaseService db, Log log) {
		this.db = db;
		this.log = log;
	}
	
	public void updateArchive() {
		// TODO: Implement
	}
	
	public void updateDatabase(ZonedDateTime timeStamp) {
		// TODO: Implement
	}
	
	public void updateObject(ZonedDateTime timeStamp, Boolean deleted, String uuid) {
		// TODO: Implement
	}
	
	public void updateClass(ZonedDateTime timeStamp, Boolean deleted, String class_name) {
		// TODO: Implement
	}
	
	public void updateDescriptor(ZonedDateTime timeStamp, Boolean deleted, String uuid, String descriptor_name) {
		// TODO: Implement
	}
	
	public void updateClassDescriptor(ZonedDateTime timeStamp, Boolean deleted, String class_name, String descriptor_name) {
		// TODO: Implement
	}
	
	public void updateMembership(ZonedDateTime timeStamp, Boolean deleted, String uuid, String class_name) {
		// TODO: Implement
	}
	
	public void updateRelationship(ZonedDateTime timeStamp, Boolean deleted, String uuid_source, String uuid_target) {
		// TODO: Implement
	}
	
	public void updateClassRelationship(ZonedDateTime timeStamp, Boolean deleted, String class_name_source, String class_name_target, String label) {
		// TODO: Implement
	}
	
}
