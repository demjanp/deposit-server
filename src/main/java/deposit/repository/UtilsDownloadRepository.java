package deposit.repository;

import deposit.common.FileSystem;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.util.Map;
import java.util.NoSuchElementException;

public class UtilsDownloadRepository extends BaseRepository {

	private static UtilsDownloadRepository instance;

	private UtilsDownloadRepository() {}

	public static synchronized UtilsDownloadRepository getInstance() {
		if (instance == null) {
			instance = new UtilsDownloadRepository();
		}
		return instance;
	}

	public String utilsDownload(Transaction tx, String namespaceName, String descriptorId) {
		String matchQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object)-[:HAS_DESCRIPTOR]->(:Descriptor {id: $descriptorId})-[:HAS_RESOURCE]->(resource:DResource)
			RETURN resource.storedFilename AS storedFilename LIMIT 1
		""";
		Map<String, Object> params = Map.of(
			"namespaceName", namespaceName,
			"descriptorId", descriptorId
		);

		try (Result result = tx.execute(matchQuery, params)) {
			if (result.hasNext()) {
				Map<String, Object> row = result.next();
				String storedFilename = (String) row.get("storedFilename");
				if (storedFilename != null) {
					return FileSystem.readFromPermanent(storedFilename);
				} else {
					throw new NoSuchElementException("Resource not found for descriptor: " + descriptorId);
				}
			} else {
				throw new NoSuchElementException("No result found for descriptor: " + descriptorId);
			}
		} catch (Exception e) {
			throwException("Error downloading resource for Descriptor: " + descriptorId, e);
		}
		return null;
	}
}
