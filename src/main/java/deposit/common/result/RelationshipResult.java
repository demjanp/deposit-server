package deposit.common.result;

import org.neo4j.graphdb.Node;

public class RelationshipResult {
	public Node source;
	public Node target;
	public Node relationship;

	public RelationshipResult(Node source, Node target, Node relationship) {
		this.source = source;
		this.target = target;
		this.relationship = relationship;
	}
}
