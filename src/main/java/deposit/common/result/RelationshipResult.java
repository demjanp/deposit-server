package deposit.common.result;

import org.neo4j.graphdb.Relationship;

public class RelationshipResult {
	public Relationship rel;

	public RelationshipResult(Relationship rel) {
		this.rel = rel;
	}
}
