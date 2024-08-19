package deposit.api;

import deposit.service.UtilsWktToCoordinatesService;
import deposit.common.result.ListResult;

import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;

import java.util.List;
import java.util.stream.Stream;

public class UtilsWktToCoordinatesAPI extends BaseAPI {
	/**
	 * Convert a WKT to a list of coordinates.
	 *
	 * @param wkt well-known-text string.
	 * @return the coordinates.
	 * @throws RuntimeException if there is an error converting the value.
	 */
	@Procedure(name = "deposit.utils.wktToCoordinates", mode = Mode.READ)
	@Description("CALL deposit.utils.wktToCoordinates(wkt) YIELD value AS coordinates")
	public Stream<ListResult> utilsWktToCoordinates(
			@Name("wkt") String wkt) {
		UtilsWktToCoordinatesService service = UtilsWktToCoordinatesService.getInstance();
		List<Object> coordinates = service.utilsWktToCoordinates(wkt);
		return Stream.of(new ListResult(coordinates));
	}
}
