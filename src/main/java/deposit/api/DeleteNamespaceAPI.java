package deposit.api;

import deposit.service.DeleteNamespaceService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class DeleteNamespaceAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Deletes a Namespace.
	 *
	 * @param namespaceName the name of the Namespace to delete.
	 * @param token authentication token.
	 * @throws RuntimeException if there is an error during deletion.
	 */
	@Procedure(name = "deposit.delete.namespace", mode = Mode.WRITE)
	@Description("CALL deposit.delete.namespace(namespaceName, token)")
	public void deleteNamespace(
			@Name("namespaceName") String namespaceName,
			@Name(value = "token", defaultValue = "") String token) {
		DeleteNamespaceService service = DeleteNamespaceService.getInstance();
		service.deleteNamespace(tx, namespaceName, token);
	}
}
