package deposit.api;

import deposit.service.SetActiveNamespaceService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class SetActiveNamespaceAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Sets the active Namespace.
	 *
	 * @param namespaceName name of the Namespace.
	 * @param token authentication token.
	 * @throws RuntimeException if Namespace doesn't exist.
	 */
	@Procedure(name = "deposit.set.activeNamespace", mode = Mode.WRITE)
	@Description("CALL deposit.set.activeNamespace(namespaceName, token)")
	public void setActiveNamespace(
			@Name("namespaceName") String namespaceName,
			@Name(value = "token", defaultValue = "") String token) {
		SetActiveNamespaceService service = SetActiveNamespaceService.getInstance();
		service.setActiveNamespace(tx, namespaceName, token);
	}
}
