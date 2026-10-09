package deposit.api;

import deposit.service.UtilsCoordinatesToWKTService;
import deposit.common.result.StringResult;

import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.Name;

import java.util.stream.Stream;
import java.util.List;

public class UtilsCoordinatesToWKTAPI extends BaseAPI {
	/**
	 * Convert a list of coordinates into a WKT.
	 *
	 * @param type geometry type.
	 * @param coordinates a list or list of lists of coordinates.
	 * @return the WKT.
	 * @throws RuntimeException if there is an error converting the value.
	 */
	@Procedure(name = "deposit.utils.coordinatesToWKT", mode = Mode.READ)
	@Description("CALL deposit.utils.coordinatesToWKT(type, coordinates) YIELD value AS wkt")
	public Stream<StringResult> utilsCoordinatesToWKT(
			@Name("type") String type,
			@Name("coordinates") List<Object> coordinates) {
		UtilsCoordinatesToWKTService service = UtilsCoordinatesToWKTService.getInstance();
		String wkt = service.utilsCoordinatesToWKT(type, coordinates);
		return Stream.of(new StringResult(wkt));
	}
}
