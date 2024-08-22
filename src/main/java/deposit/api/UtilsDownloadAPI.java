package deposit.api;

import deposit.service.UtilsDownloadService;
import deposit.common.result.StreamResult;

import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;
import org.neo4j.graphdb.Transaction;

import java.util.stream.Stream;

public class UtilsDownloadAPI extends BaseAPI {

	@Context
	public Transaction tx;
	/**
	 * Download a file resource attached to the Descriptor.
	 *
	 * @param descriptor the Descriptor with the DResource value. Can be a Descriptor node or Id.
	 * @param token authentication token.
	 * @return the content of the file as a Base64 encoded string.
	 * @throws RuntimeException if there was an error retrieving the file resource.
	 */
	@Procedure(name = "deposit.utils.download", mode = Mode.READ)
	@Description("CALL deposit.utils.download(descriptor, token) YIELD stream")
	public Stream<StreamResult> utilsDownload(
			@Name("descriptor") Object descriptor,
			@Name(value = "token", defaultValue = "") String token) {
		UtilsDownloadService service = UtilsDownloadService.getInstance();
		String base64Stream = service.utilsDownload(tx, descriptor, token);
		return Stream.of(new StreamResult(base64Stream));
	}
}
