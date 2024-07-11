package deposit.service;

import deposit.repository.ImportRepository;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.logging.Log;

import java.util.List;
import java.util.Map;

public class ImportService {

	private ImportRepository importRepository;

	public ImportService(GraphDatabaseService db, Log log) {
		this.importRepository = new ImportRepository(db, log);
	}

    // Package-private setter for ImportRepository to be used in tests
    void setImportRepository(ImportRepository importRepository) {
        this.importRepository = importRepository;
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
