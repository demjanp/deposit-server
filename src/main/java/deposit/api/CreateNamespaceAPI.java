package deposit.api;

import deposit.service.CreateNamespaceService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class CreateNamespaceAPI extends BaseAPI {

	@Context
	public Transaction tx;

	/**
	 * Creates a new Namespace.
	 *
	 * @param namespaceName the name of the Namespace to create.
	 * @param description annotation to provide additional information about the Namespace, default is an empty string.
	 * @param versioningType type of versioning to use. Can be empty, 'transient' or 'persistent', default is an empty string.
	 * @param token authentication token.
	 * @return the created Namespace node.
	 * @throws RuntimeException if a namespace with that name already exists or if there is an error during creation.
	 */
	@Procedure(name = "deposit.create.namespace", mode = Mode.WRITE)
	@Description("CALL deposit.create.namespace(namespaceName, description, versioningType, token) YIELD node AS namespace")
	public Stream<NodeResult> createNamespace(
			@Name("namespaceName") String namespaceName,
			@Name(value = "description", defaultValue = "") String description,
			@Name(value = "versioningType", defaultValue = "") String versioningType,
			@Name(value = "token", defaultValue = "") String token) {
		CreateNamespaceService service = CreateNamespaceService.getInstance();
		Node node = service.createNamespace(tx, namespaceName, description, versioningType, token);
		return Stream.of(new NodeResult(node));
	}
}
