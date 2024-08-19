package deposit.api;

import deposit.BaseNeo4jTest;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class UtilsWktToCoordinatesAPITest extends BaseNeo4jTest {

	@Before
	public void setUp() {
		super.setUp();
	}

	@Test
	public void shouldConvertPointWKTToCoordinates() {
		try (Session session = driver.session()) {
			String wkt = "POINT (12.9715987 77.594566)";
			Result result = session.run("CALL deposit.utils.wktToCoordinates($wkt) YIELD value AS coordinates",
					Map.of("wkt", wkt));

			List<Object> coordinates = result.single().get("coordinates").asList();
			assertNotNull("Coordinates should not be null", coordinates);
			assertEquals(2, coordinates.size());
			assertEquals(12.9715987, (double) coordinates.get(0), 0.000001);
			assertEquals(77.594566, (double) coordinates.get(1), 0.000001);
		}
	}

	@Test
	public void shouldConvertLineStringWKTToCoordinates() {
		try (Session session = driver.session()) {
			String wkt = "LINESTRING (12.9715987 77.594566, 13.035 77.597, 13.015 77.598)";
			Result result = session.run("CALL deposit.utils.wktToCoordinates($wkt) YIELD value AS coordinates",
					Map.of("wkt", wkt));

			List<Object> coordinates = result.single().get("coordinates").asList();
			assertNotNull("Coordinates should not be null", coordinates);
			assertEquals(3, coordinates.size());

			List<Object> firstPoint = (List<Object>) coordinates.get(0);
			assertEquals(12.9715987, (double) firstPoint.get(0), 0.000001);
			assertEquals(77.594566, (double) firstPoint.get(1), 0.000001);

			List<Object> secondPoint = (List<Object>) coordinates.get(1);
			assertEquals(13.035, (double) secondPoint.get(0), 0.000001);
			assertEquals(77.597, (double) secondPoint.get(1), 0.000001);
		}
	}

	@Test
	public void shouldConvertPolygonWKTToCoordinates() {
		try (Session session = driver.session()) {
			String wkt = "POLYGON ((12.9715987 77.594566, 13.035 77.597, 13.015 77.598, 12.9715987 77.594566))";
			Result result = session.run("CALL deposit.utils.wktToCoordinates($wkt) YIELD value AS coordinates",
					Map.of("wkt", wkt));

			List<Object> coordinates = result.single().get("coordinates").asList();
			assertNotNull("Coordinates should not be null", coordinates);
			assertEquals(1, coordinates.size());

			List<Object> ring = (List<Object>) coordinates.get(0);
			assertEquals(4, ring.size());

			List<Object> firstPoint = (List<Object>) ring.get(0);
			assertEquals(12.9715987, (double) firstPoint.get(0), 0.000001);
			assertEquals(77.594566, (double) firstPoint.get(1), 0.000001);
		}
	}

	@Test
	public void shouldConvertMultiPointWKTToCoordinates() {
		try (Session session = driver.session()) {
			String wkt = "MULTIPOINT ((12.9715987 77.594566), (13.035 77.597), (13.015 77.598))";
			Result result = session.run("CALL deposit.utils.wktToCoordinates($wkt) YIELD value AS coordinates",
					Map.of("wkt", wkt));

			List<Object> coordinates = result.single().get("coordinates").asList();
			assertNotNull("Coordinates should not be null", coordinates);
			assertEquals(3, coordinates.size());

			List<Object> firstPoint = (List<Object>) coordinates.get(0);
			assertEquals(12.9715987, (double) firstPoint.get(0), 0.000001);
			assertEquals(77.594566, (double) firstPoint.get(1), 0.000001);
		}
	}

	@Test
	public void shouldConvertMultiLineStringWKTToCoordinates() {
		try (Session session = driver.session()) {
			String wkt = "MULTILINESTRING ((12.9715987 77.594566, 13.035 77.597), (13.015 77.598, 12.9715987 77.594566))";
			Result result = session.run("CALL deposit.utils.wktToCoordinates($wkt) YIELD value AS coordinates",
					Map.of("wkt", wkt));

			List<Object> coordinates = result.single().get("coordinates").asList();
			assertNotNull("Coordinates should not be null", coordinates);
			assertEquals(2, coordinates.size());
		}
	}

	@Test
	public void shouldConvertMultiPolygonWKTToCoordinates() {
		try (Session session = driver.session()) {
			String wkt = "MULTIPOLYGON (((12.9715987 77.594566, 13.035 77.597, 13.015 77.598, 12.9715987 77.594566)))";
			Result result = session.run("CALL deposit.utils.wktToCoordinates($wkt) YIELD value AS coordinates",
					Map.of("wkt", wkt));

			List<Object> coordinates = result.single().get("coordinates").asList();
			assertNotNull("Coordinates should not be null", coordinates);
			assertEquals(1, coordinates.size());
		}
	}

	@Test
	public void shouldConvertGeometryCollectionWKTToCoordinates() {
		try (Session session = driver.session()) {
			String wkt = "GEOMETRYCOLLECTION (POINT (12.9715987 77.594566), LINESTRING (12.9715987 77.594566, 13.035 77.597))";
			Result result = session.run("CALL deposit.utils.wktToCoordinates($wkt) YIELD value AS coordinates",
					Map.of("wkt", wkt));

			List<Object> coordinates = result.single().get("coordinates").asList();
			assertNotNull("Coordinates should not be null", coordinates);
			assertEquals(2, coordinates.size());
		}
	}

	@Test
	public void shouldConvertTriangleWKTToCoordinates() {
		try (Session session = driver.session()) {
			String wkt = "POLYGON ((12.9715987 77.594566, 13.035 77.597, 13.015 77.598, 12.9715987 77.594566))";
			Result result = session.run("CALL deposit.utils.wktToCoordinates($wkt) YIELD value AS coordinates",
					Map.of("wkt", wkt));

			List<Object> coordinates = result.single().get("coordinates").asList();
			assertNotNull("Coordinates should not be null", coordinates);
			assertEquals(1, coordinates.size());
		}
	}

	@Test
	public void shouldConvertMultiCurveWKTToCoordinates() {
		try (Session session = driver.session()) {
			String wkt = "MULTILINESTRING ((12.9715987 77.594566, 13.035 77.597), (13.015 77.598, 12.9715987 77.594566))";
			Result result = session.run("CALL deposit.utils.wktToCoordinates($wkt) YIELD value AS coordinates",
					Map.of("wkt", wkt));

			List<Object> coordinates = result.single().get("coordinates").asList();
			assertNotNull("Coordinates should not be null", coordinates);
			assertEquals(2, coordinates.size());
		}
	}

	@Test
	public void shouldConvertMultiSurfaceWKTToCoordinates() {
		try (Session session = driver.session()) {
			String wkt = "MULTIPOLYGON (((12.9715987 77.594566, 13.035 77.597, 13.015 77.598, 12.9715987 77.594566)), ((12.9715987 77.594566, 13.035 77.597, 13.015 77.598, 12.9715987 77.594566)))";
			Result result = session.run("CALL deposit.utils.wktToCoordinates($wkt) YIELD value AS coordinates",
					Map.of("wkt", wkt));

			List<Object> coordinates = result.single().get("coordinates").asList();
			assertNotNull("Coordinates should not be null", coordinates);
			assertEquals(2, coordinates.size());
		}
	}
}
