package deposit.api;

import deposit.service.SetMembershipService;
import deposit.common.result.RelationshipResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Relationship;

import java.util.stream.Stream;

public class SetMembershipAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Set the Membership of an Object in a Class.
	 *
	 * @param className the name of the Class.
	 * @param objectId the Id of the Object.
	 * @param token authentication token.
	 * @return the created Membership relationship.
	 * @throws RuntimeException if the Membership already exists or if there is an error during the creation.
	 */
	@Procedure(name = "deposit.set.membership", mode = Mode.WRITE)
	@Description("CALL deposit.set.membership(className, objectId, token) YIELD rel")
	public Stream<RelationshipResult> createMembership(
			@Name("className") String className,
			@Name("objectId") String objectId,
			@Name(value = "token", defaultValue = "") String token) {
		SetMembershipService service = SetMembershipService.getInstance();
		Relationship rel = service.setMembership(tx, className, objectId, token);
		return Stream.of(new RelationshipResult(rel));
	}
}
