package deposit.api;

import deposit.service.SetActiveNamespaceService;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

public class SetActiveNamespaceAPI {

	@Context
	public Transaction tx;
	/**
	 * Sets the active Namespace.
	 *
	 * @param name name of the Namespace.
	 * @throws RuntimeException if Namespace doesn't exist.
	 */
	@Procedure(name = "deposit.set.activeNamespace", mode = Mode.WRITE)
	@Description("CALL deposit.set.activeNamespace(name)")
	public void setActiveNamespace(
			@Name("name") String name) {
		SetActiveNamespaceService service = SetActiveNamespaceService.getInstance();
		service.setActiveNamespace(tx, name);
	}
}
