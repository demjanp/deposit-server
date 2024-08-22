package deposit.api;

import deposit.service.SetNamespaceDescriptionService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class SetNamespaceDescriptionAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Set description of the active Namespace.
	 *
	 * @param description annotation to provide additional information about the Namespace.
	 * @param token authentication token.
	 * @throws RuntimeException if there is an error setting the description.
	 */
	@Procedure(name = "deposit.set.namespaceDescription", mode = Mode.WRITE)
	@Description("CALL deposit.set.namespaceDescription(description, token)")
	public void setNamespaceDescription(
			@Name("description") String description,
			@Name(value = "token", defaultValue = "") String token) {
		SetNamespaceDescriptionService service = SetNamespaceDescriptionService.getInstance();
		service.setNamespaceDescription(tx, description, token);
	}
}
