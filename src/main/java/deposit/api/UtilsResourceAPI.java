package deposit.api;

import deposit.service.UtilsResourceService;
import deposit.common.result.NodeResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;

import java.util.stream.Stream;

public class UtilsResourceAPI extends BaseAPI {
	
	@Context
	public Transaction tx;
	/**
	 * Store a file resource in transient storage on the server and create a DResource node.
	 *
	 * @param stream the content of the file as a Base64 encoded string.
	 * @param filename the name of the file.
	 * @param token authentication token.
	 * @return the created DResource node.
	 * @throws RuntimeException if there was an error uploading the file.
	 */
	@Procedure(name = "deposit.utils.resource", mode = Mode.WRITE)
	@Description("CALL deposit.utils.resource(stream, filename, token) YIELD resource")
	public Stream<NodeResult> utilsResource(
			@Name("stream") String stream,
			@Name("filename") String filename,
			@Name(value = "token", defaultValue = "") String token) {
		UtilsResourceService service = UtilsResourceService.getInstance();
		Node node = service.utilsResource(tx, stream, filename, token);
		return Stream.of(new NodeResult(node));
	}
}
