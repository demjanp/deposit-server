package deposit.common.result;

import org.neo4j.graphdb.Relationship;

public class MembershipResult {
	public Relationship membership;

	public MembershipResult(Relationship membership) {
		this.membership = membership;
	}
}
