package deposit.api;

import deposit.service.CreateClassRelationshipService;
import deposit.common.result.RelationshipResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Relationship;

import java.util.stream.Stream;

public class CreateClassRelationshipAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Create a Class Relationship.
	 *
	 * @param startClassName the name of the start Class.
	 * @param endClassName the name of the end Class.
	 * @param label the label of the Relationship.
	 * @param comment annotation to provide additional information about the Relationship, default is an empty string.
	 * @param token authentication token.
	 * @return the created Relationship.
	 * @throws RuntimeException if the Class Relationship already exists or if there is an error during the creation.
	 */
	@Procedure(name = "deposit.create.classRelationship", mode = Mode.WRITE)
	@Description("CALL deposit.create.classRelationship(startClassName, endClassName, label, comment, token) YIELD rel")
	public Stream<RelationshipResult> createClassRelationship(
			@Name("startClassName") String startClassName,
			@Name("endClassName") String endClassName,
			@Name("label") String label,
			@Name(value = "comment", defaultValue = "") String comment,
			@Name(value = "token", defaultValue = "") String token) {
		CreateClassRelationshipService service = CreateClassRelationshipService.getInstance();
		Relationship rel = service.createClassRelationship(tx, startClassName, endClassName, label, comment, token);
		return Stream.of(new RelationshipResult(rel));
	}
}
