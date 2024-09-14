package deposit.repository;

import org.neo4j.graphdb.*;

import java.util.Collections;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.List;

public class GetQueryRepository extends BaseRepository {

	private static GetQueryRepository instance;
	
	private GetQueryRepository() {}
	
	public static synchronized GetQueryRepository getInstance() {
		if (instance == null) {
			instance = new GetQueryRepository();
		}
		return instance;
	}

    public Stream<Node[]> getQuery(Transaction tx, String namespaceName, String cypherQueryStr, List<String> returnedNodes) {
		try {
			Result result = tx.execute(cypherQueryStr, Map.of("namespaceName", namespaceName));

			return StreamSupport.stream(result.stream().spliterator(), false)
				.map(row -> returnedNodes.stream()
					.map(name -> {
						Object value = row.get(name);
						if (value instanceof Node) {
							return (Node) value;
						} else {
							return new VirtualNode(value);
						}
					})
					.toArray(Node[]::new));
		} catch (Exception e) {
			throwException("Error executing query: " + cypherQueryStr, e);
		}
		return Stream.empty();
	}

	private class VirtualNode implements Node {

		private final Object value;

		// Constructor to initialize the 'value' property
		public VirtualNode(Object value) {
			this.value = value;
		}

		// Getter for the 'value' property
		public Object getValue() {
			return value;
		}

		// Implement the getProperty method to return the 'value'
		@Override
		public Object getProperty(String key) {
			if ("value".equals(key)) {
				return value;
			}
			throw new IllegalArgumentException("No such property: " + key);
		}

		@Override
		public Object getProperty(String s, Object o) {
			return null;
		}

		@Override
		public boolean hasProperty(String key) {
			return "value".equals(key);
		}

		@Override
		public void setProperty(String key, Object value) {
			throw new UnsupportedOperationException("VirtualNode is read-only.");
		}

		@Override
		public Object removeProperty(String s) {
			return null;
		}

		// Implement other Node methods as no-ops or throw UnsupportedOperationException
		@Override
		public Iterable<String> getPropertyKeys() {
			return Collections.singletonList("value");
		}

		@Override
		public Map<String, Object> getProperties(String... strings) {
			return Map.of();
		}

		@Override
		public Map<String, Object> getAllProperties() {
			return Map.of();
		}

		@Override
		public long getId() {
			return 0;
		}

		@Override
		public String getElementId() {
			return "";
		}

		@Override
		public void delete() {
			throw new UnsupportedOperationException("VirtualNode cannot be deleted.");
		}

		@Override
		public ResourceIterable<Relationship> getRelationships() {
			return null;
		}

		@Override
		public boolean hasRelationship() {
			return false;
		}

		@Override
		public ResourceIterable<Relationship> getRelationships(RelationshipType... relationshipTypes) {
			return null;
		}

		@Override
		public ResourceIterable<Relationship> getRelationships(Direction direction, RelationshipType... relationshipTypes) {
			return null;
		}

		@Override
		public boolean hasRelationship(RelationshipType... relationshipTypes) {
			return false;
		}

		@Override
		public boolean hasRelationship(Direction direction, RelationshipType... relationshipTypes) {
			return false;
		}

		@Override
		public ResourceIterable<Relationship> getRelationships(Direction direction) {
			return null;
		}

		@Override
		public boolean hasRelationship(Direction direction) {
			return false;
		}

		@Override
		public Relationship getSingleRelationship(RelationshipType relationshipType, Direction direction) {
			return null;
		}

		@Override
		public Relationship createRelationshipTo(Node node, RelationshipType relationshipType) {
			return null;
		}

		@Override
		public Iterable<RelationshipType> getRelationshipTypes() {
			return null;
		}

		@Override
		public int getDegree() {
			return 0;
		}

		@Override
		public int getDegree(RelationshipType relationshipType) {
			return 0;
		}

		@Override
		public int getDegree(Direction direction) {
			return 0;
		}

		@Override
		public int getDegree(RelationshipType relationshipType, Direction direction) {
			return 0;
		}

		@Override
		public void addLabel(Label label) {

		}

		@Override
		public void removeLabel(Label label) {

		}

		@Override
		public boolean hasLabel(Label label) {
			return false;  // No labels for VirtualNode
		}

		@Override
		public Iterable<Label> getLabels() {
			return Collections.emptyList();  // No labels for VirtualNode
		}

		// Add other necessary methods if needed, throwing UnsupportedOperationException for non-applicable ones
	}
}
