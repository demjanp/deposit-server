package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class GetObjectRepository extends BaseRepository {

	private static GetObjectRepository instance;

	private GetObjectRepository() {}

	public static synchronized GetObjectRepository getInstance() {
		if (instance == null) {
			instance = new GetObjectRepository();
		}
		return instance;
	}

	public Stream<Node> getObject(Transaction tx, String namespaceName, String className, List<List<Object>> descriptors) {
		StringBuilder matchQuery = new StringBuilder();
		Map<String, Object> params = new HashMap<>();

		// Basic MATCH clause for the Namespace and Object
		matchQuery.append("MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(existing:Object)");

		// Handle classes if provided
		if (!className.isEmpty()) {
			matchQuery.append("-[:MEMBER_OF]->(class:Class {name: $className})");
			params.put("className", className);
		}

		// Handle descriptors if provided
		if (descriptors != null && !descriptors.isEmpty()) {
			for (int i = 0; i < descriptors.size(); i++) {
				List<Object> descriptor = descriptors.get(i);
				if (descriptor.size() == 2) {
					String descriptorName = "descriptorName" + i;
					String descriptorValue = "descriptorValue" + i;
					matchQuery.append(" MATCH (existing)-[:HAS_DESCRIPTOR]->(descriptor:Descriptor {name: $").append(descriptorName).append(", value: $").append(descriptorValue).append("})");
					params.put(descriptorName, descriptor.get(0));
					params.put(descriptorValue, descriptor.get(1));
				}
			}
		}

		// Final return clause
		matchQuery.append(" RETURN existing");

		// Add the namespaceName parameter
		params.put("namespaceName", namespaceName);

		try {
			Result result = tx.execute(matchQuery.toString(), params);
			return StreamSupport.stream(result.stream().spliterator(), false)
								.map(r -> (Node) r.get("existing"));
		} catch (Exception e) {
			throwException("Error retrieving Objects", e);
		}
		return Stream.empty();
	}
}
