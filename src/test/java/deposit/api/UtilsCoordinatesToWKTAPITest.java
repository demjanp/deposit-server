package deposit.api;

import deposit.BaseNeo4jTest;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class UtilsCoordinatesToWKTAPITest extends BaseNeo4jTest {

	@Before
	public void setUp() {
		super.setUp();
	}

	@Test
	public void shouldConvertPointToWKT() {
		try (Session session = driver.session()) {
			List<Object> coordinates = List.of(12.9715987, 77.594566);
			Result result = session.run("CALL deposit.utils.coordinatesToWKT('Point', $coordinates) YIELD value AS wkt",
					Map.of("coordinates", coordinates));

			String wkt = result.single().get("wkt").asString();
			assertNotNull("WKT should not be null", wkt);
			assertFalse("WKT should not be empty", wkt.isEmpty());
		}
	}

	@Test
	public void shouldConvertLineStringToWKT() {
		try (Session session = driver.session()) {
			List<Object> coordinates = List.of(
					List.of(12.9715987, 77.594566),
					List.of(13.035, 77.597),
					List.of(13.015, 77.598)
			);
			Result result = session.run("CALL deposit.utils.coordinatesToWKT('LineString', $coordinates) YIELD value AS wkt",
					Map.of("coordinates", coordinates));

			String wkt = result.single().get("wkt").asString();
			assertNotNull("WKT should not be null", wkt);
			assertFalse("WKT should not be empty", wkt.isEmpty());
		}
	}

	@Test
	public void shouldConvertPolygonToWKT() {
		try (Session session = driver.session()) {
			List<Object> coordinates = List.of(
				List.of(
					List.of(12.9715987, 77.594566),
					List.of(13.035, 77.597),
					List.of(13.015, 77.598),
					List.of(12.9715987, 77.594566) // Closed ring
				)
			);
			Result result = session.run("CALL deposit.utils.coordinatesToWKT('Polygon', $coordinates) YIELD value AS wkt",
					Map.of("coordinates", coordinates));

			String wkt = result.single().get("wkt").asString();
			assertNotNull("WKT should not be null", wkt);
			assertFalse("WKT should not be empty", wkt.isEmpty());
		}
	}

	@Test
	public void shouldConvertMultiPointToWKT() {
		try (Session session = driver.session()) {
			List<Object> coordinates = List.of(
					List.of(12.9715987, 77.594566),
					List.of(13.035, 77.597),
					List.of(13.015, 77.598)
			);
			Result result = session.run("CALL deposit.utils.coordinatesToWKT('MultiPoint', $coordinates) YIELD value AS wkt",
					Map.of("coordinates", coordinates));

			String wkt = result.single().get("wkt").asString();
			assertNotNull("WKT should not be null", wkt);
			assertFalse("WKT should not be empty", wkt.isEmpty());
		}
	}

	@Test
	public void shouldConvertMultiLineStringToWKT() {
		try (Session session = driver.session()) {
			List<Object> coordinates = List.of(
					List.of(
							List.of(12.9715987, 77.594566),
							List.of(13.035, 77.597)
					),
					List.of(
							List.of(13.015, 77.598),
							List.of(12.9715987, 77.594566)
					)
			);
			Result result = session.run("CALL deposit.utils.coordinatesToWKT('MultiLineString', $coordinates) YIELD value AS wkt",
					Map.of("coordinates", coordinates));

			String wkt = result.single().get("wkt").asString();
			assertNotNull("WKT should not be null", wkt);
			assertFalse("WKT should not be empty", wkt.isEmpty());
		}
	}

	@Test
	public void shouldConvertMultiPolygonToWKT() {
		try (Session session = driver.session()) {
			List<Object> coordinates = List.of(
					List.of(
							List.of(
									List.of(12.9715987, 77.594566),
									List.of(13.035, 77.597),
									List.of(13.015, 77.598),
									List.of(12.9715987, 77.594566)
							)
					),
					List.of(
							List.of(
									List.of(12.9715987, 77.594566),
									List.of(12.9715987, 77.594566),
									List.of(12.9715987, 77.594566),
									List.of(12.9715987, 77.594566)
							)
					)
			);
			Result result = session.run("CALL deposit.utils.coordinatesToWKT('MultiPolygon', $coordinates) YIELD value AS wkt",
					Map.of("coordinates", coordinates));

			String wkt = result.single().get("wkt").asString();
			assertNotNull("WKT should not be null", wkt);
			assertFalse("WKT should not be empty", wkt.isEmpty());
		}
	}

	@Test
	public void shouldConvertGeometryCollectionToWKT() {
		try (Session session = driver.session()) {
			List<Object> coordinates = List.of(
					List.of(12.9715987, 77.594566), // Point
					List.of(
							List.of(12.9715987, 77.594566),
							List.of(13.035, 77.597),
							List.of(13.015, 77.598)
					) // LineString
			);
			Result result = session.run("CALL deposit.utils.coordinatesToWKT('GeometryCollection', $coordinates) YIELD value AS wkt",
					Map.of("coordinates", coordinates));

			String wkt = result.single().get("wkt").asString();
			assertNotNull("WKT should not be null", wkt);
			assertFalse("WKT should not be empty", wkt.isEmpty());
		}
	}

	@Test
	public void shouldConvertTriangleToWKT() {
		try (Session session = driver.session()) {
			List<Object> coordinates = List.of(
					List.of(
							List.of(12.9715987, 77.594566),
							List.of(13.035, 77.597),
							List.of(13.015, 77.598),
							List.of(12.9715987, 77.594566) // Closed ring
					)
			);
			Result result = session.run("CALL deposit.utils.coordinatesToWKT('Triangle', $coordinates) YIELD value AS wkt",
					Map.of("coordinates", coordinates));

			String wkt = result.single().get("wkt").asString();
			assertNotNull("WKT should not be null", wkt);
			assertFalse("WKT should not be empty", wkt.isEmpty());
		}
	}

	@Test
	public void shouldConvertMultiCurveToWKT() {
		try (Session session = driver.session()) {
			List<Object> coordinates = List.of(
					List.of(
							List.of(12.9715987, 77.594566),
							List.of(13.035, 77.597)
					),
					List.of(
							List.of(13.015, 77.598),
							List.of(12.9715987, 77.594566)
					)
			);
			Result result = session.run("CALL deposit.utils.coordinatesToWKT('MultiCurve', $coordinates) YIELD value AS wkt",
					Map.of("coordinates", coordinates));

			String wkt = result.single().get("wkt").asString();
			assertNotNull("WKT should not be null", wkt);
			assertFalse("WKT should not be empty", wkt.isEmpty());
		}
	}

	@Test
	public void shouldConvertMultiSurfaceToWKT() {
		try (Session session = driver.session()) {
			List<Object> coordinates = List.of(
					List.of(
							List.of(
									List.of(12.9715987, 77.594566),
									List.of(13.035, 77.597),
									List.of(13.015, 77.598),
									List.of(12.9715987, 77.594566) // Closed ring
							)
					),
					List.of(
							List.of(
									List.of(12.9715987, 77.594566),
									List.of(13.035, 77.597),
									List.of(13.015, 77.598),
									List.of(12.9715987, 77.594566) // Closed ring
							)
					)
			);
			Result result = session.run("CALL deposit.utils.coordinatesToWKT('MultiSurface', $coordinates) YIELD value AS wkt",
					Map.of("coordinates", coordinates));

			String wkt = result.single().get("wkt").asString();
			assertNotNull("WKT should not be null", wkt);
			assertFalse("WKT should not be empty", wkt.isEmpty());
		}
	}

	@Test
	public void shouldCreatePolygonsAndPointsAndPerformSpatialQuery() {
		try (Session session = driver.session()) {
			List<Object> polygonCoordinates = List.of(
				List.of(
					List.of(77.594566, 12.9715987),
					List.of(77.597, 13.035),
					List.of(77.598, 13.015),
					List.of(77.594566, 12.9715987) // Closed ring
				)
			);
			List<Object> pointCoordinates = List.of(77.594566, 12.9715987);

			// Perform the entire spatial operation in a single query
			Result queryResult = session.run("""
				CALL spatial.addWKTLayer('geomLayer', '{}') YIELD node AS layerNode
				CALL deposit.utils.coordinatesToWKT('Polygon', $polygonCoordinates) YIELD value AS wkt_polygon
				CALL spatial.addWKT('geomLayer', wkt_polygon) YIELD node AS polygonNode
				CALL deposit.utils.coordinatesToWKT('Point', $pointCoordinates) YIELD value AS wkt_point
				CALL spatial.addWKT('geomLayer', wkt_point) YIELD node AS pointNode
				CALL spatial.withinDistance('geomLayer', point({latitude: 12.9715987, longitude: 77.594566}), 100) 
				YIELD node
				RETURN node
			""", Map.of(
					"polygonCoordinates", polygonCoordinates,
					"pointCoordinates", pointCoordinates
				)
			);

			assertTrue("The spatial query should return the point within the polygon", queryResult.hasNext());
			Map<String, Object> resultNode = queryResult.next().asMap();
			assertNotNull("The result should contain a node", resultNode);
		}
	}
}
