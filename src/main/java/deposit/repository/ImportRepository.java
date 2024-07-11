package deposit.repository;

import deposit.core.*;
import org.neo4j.graphdb.*;
import org.neo4j.logging.Log;

import java.util.List;
import java.util.Map;

public class ImportRepository {
	
	private final GraphDatabaseService db;
	private final Log log;
	
	public ImportRepository(GraphDatabaseService db, Log log) {
		this.db = db;
		this.log = log;
	}
	
	public void importDataRow(Map<String, Object> data, Map<String, Object> relationships, List<String> unique, List<String> update, Boolean matchEmpty) {
		// TODO: Implement
	}
	
	public void importSubTree(Map<String, Map<String, Object>> subtree, List<String> unique, Boolean matchEmpty) {
		// TODO: Implement
	}
	
	public void importDatabase(String name, List<String> unique, Boolean matchEmpty) {
		// TODO: Implement
	}
}
