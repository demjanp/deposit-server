package deposit.api;

import deposit.service.CreateMembershipService;
import deposit.common.result.MembershipResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Relationship;

import java.util.stream.Stream;

public class CreateMembershipAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Create a Class Membership.
	 *
	 * @param className the name of the Class.
	 * @param objectId the Id of the Object.
	 * @param token authentication token.
	 * @return the created Membership relationship.
	 * @throws RuntimeException if the Membership already exists or if there is an error during the creation.
	 */
	@Procedure(name = "deposit.create.membership", mode = Mode.WRITE)
	@Description("CALL deposit.create.membership(className, objectId, token) YIELD membership")
	public Stream<MembershipResult> createMembership(
			@Name("className") String className,
			@Name("objectId") String objectId,
			@Name(value = "token", defaultValue = "") String token) {
		CreateMembershipService service = CreateMembershipService.getInstance();
		Relationship relationship = service.createMembership(tx, className, objectId, token);
		return Stream.of(new MembershipResult(relationship));
	}
}
