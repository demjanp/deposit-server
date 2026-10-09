package deposit.service;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.WKTWriter;

import java.util.List;

public class UtilsCoordinatesToWKTService extends BaseService {

	private static UtilsCoordinatesToWKTService instance;

	private UtilsCoordinatesToWKTService() {
	}

	public static synchronized UtilsCoordinatesToWKTService getInstance() {
		if (instance == null) {
			instance = new UtilsCoordinatesToWKTService();
		}
		return instance;
	}

	public String utilsCoordinatesToWKT(String type, List<Object> coordinates) {
		try {
			validateType(type);
			validateCoordinates(type, coordinates);

			Geometry geometry = createGeometry(type, coordinates);
			
			WKTWriter wktWriter = new WKTWriter();
			String wkt = wktWriter.write(geometry);
			return wkt;
		} catch (Exception e) {
			throwException("An error has occurred while converting coordinates to WKT.", e);
			return null;
		}
	}

	private void validateType(String type) {
		if (type == null || type.isEmpty()) {
			throwException("Geometry type cannot be null or empty.");
		}
		switch (type) {
			case "Point":
			case "LineString":
			case "Polygon":
			case "MultiPoint":
			case "MultiLineString":
			case "MultiPolygon":
			case "GeometryCollection":
			case "Triangle":
			case "MultiCurve":
			case "MultiSurface":
				// Valid types
				break;
			default:
				throwException("Invalid geometry type: " + type);
		}
	}

	private void validateCoordinates(String type, List<Object> coordinates) {
		if (coordinates == null || coordinates.isEmpty()) {
			throwException("Coordinates cannot be null or empty.");
		}

		switch (type) {
			case "Point":
				if (coordinates.size() != 2 || !(coordinates.get(0) instanceof Number) || !(coordinates.get(1) instanceof Number)) {
					throwException("Invalid coordinates for Point. Expected [x, y].");
				}
				break;
			case "LineString":
			case "MultiPoint":
				validateCoordinateList(coordinates, 2);
				break;
			case "Polygon":
			case "MultiLineString":
			case "Triangle":
				for (Object ring : coordinates) {
					if (!(ring instanceof List)) {
						throwException("Invalid coordinates for " + type + ". Expected a list of lists.");
					}
					validateCoordinateList((List<Object>) ring, 2);
				}
				break;
			case "MultiPolygon":
				for (Object polygon : coordinates) {
					if (!(polygon instanceof List)) {
						throwException("Invalid coordinates for MultiPolygon. Expected a list of polygons.");
					}
					for (Object ring : (List<Object>) polygon) {
						if (!(ring instanceof List)) {
							throwException("Invalid coordinates for MultiPolygon. Expected a list of lists.");
						}
						validateCoordinateList((List<Object>) ring, 2);
					}
				}
				break;
			case "GeometryCollection":
				for (Object geometry : coordinates) {
					if (!(geometry instanceof List)) {
						throwException("Invalid coordinates for GeometryCollection. Expected a list of geometries.");
					}
					// Recursively validate each geometry in the collection
					validateCoordinates(determineGeometryType((List<Object>) geometry), (List<Object>) geometry);
				}
				break;
			case "MultiCurve":
			case "MultiSurface":
				for (Object curveOrSurface : coordinates) {
					if (!(curveOrSurface instanceof List)) {
						throwException("Invalid coordinates for " + type + ". Expected a list of curves or surfaces.");
					}
					validateCoordinates(determineGeometryType((List<Object>) curveOrSurface), (List<Object>) curveOrSurface);
				}
				break;
			default:
				throwException("Unsupported geometry type: " + type);
		}
	}

	private void validateCoordinateList(List<Object> coordinates, int dimension) {
		for (Object coordinate : coordinates) {
			if (!(coordinate instanceof List)) {
				throwException("Invalid coordinate format. Expected a list of " + dimension + " values.");
			}
			List<?> coordList = (List<?>) coordinate;
			if (coordList.size() != dimension) {
				throwException("Invalid coordinate dimension. Expected " + dimension + " values, but got " + coordList.size() + ".");
			}
			for (Object value : coordList) {
				if (!(value instanceof Number)) {
					throwException("Coordinate values must be numbers.");
				}
			}
		}
	}

	private Geometry createGeometry(String type, List<Object> coordinates) {
		GeometryFactory geometryFactory = new GeometryFactory();

		switch (type) {
			case "Point":
				return geometryFactory.createPoint(createCoordinate((List<Object>) coordinates));
			case "LineString":
				return geometryFactory.createLineString(createCoordinateArray(coordinates));
			case "Polygon":
			case "Triangle":
				return geometryFactory.createPolygon(createLinearRing((List<Object>) coordinates.get(0), geometryFactory));
			case "MultiPoint":
				return geometryFactory.createMultiPointFromCoords(createCoordinateArray(coordinates));
			case "MultiLineString":
				return geometryFactory.createMultiLineString(createLineStrings(coordinates, geometryFactory));
			case "MultiPolygon":
				return geometryFactory.createMultiPolygon(createPolygons(coordinates, geometryFactory));
			case "GeometryCollection":
				return geometryFactory.createGeometryCollection(createGeometries(coordinates, geometryFactory));
			case "MultiCurve":
				return geometryFactory.createGeometryCollection(createCurves(coordinates, geometryFactory));
			case "MultiSurface":
				return geometryFactory.createGeometryCollection(createSurfaces(coordinates, geometryFactory));
			default:
				throwException("Unsupported or unimplemented geometry type: " + type);
				return null; // Unreachable but required by the compiler
		}
	}


	private Coordinate createCoordinate(List<Object> coordinate) {
		return new Coordinate(((Number) coordinate.get(0)).doubleValue(), ((Number) coordinate.get(1)).doubleValue());
	}

	private Coordinate[] createCoordinateArray(List<Object> coordinates) {
		return coordinates.stream()
				.map(coord -> createCoordinate((List<Object>) coord))
				.toArray(Coordinate[]::new);
	}

	private LinearRing createLinearRing(List<Object> coordinates, GeometryFactory factory) {
		return factory.createLinearRing(createCoordinateArray(coordinates));
	}

	private LineString[] createLineStrings(List<Object> coordinates, GeometryFactory factory) {
		return coordinates.stream()
				.map(line -> factory.createLineString(createCoordinateArray((List<Object>) line)))
				.toArray(LineString[]::new);
	}

	private Polygon[] createPolygons(List<Object> coordinates, GeometryFactory factory) {
		return coordinates.stream()
				.map(polygon -> factory.createPolygon(createLinearRing((List<Object>) ((List<Object>) polygon).get(0), factory)))
				.toArray(Polygon[]::new);
	}

	private Geometry[] createGeometries(List<Object> coordinates, GeometryFactory factory) {
		return coordinates.stream()
				.map(geometry -> {
					String subType = determineGeometryType((List<Object>) geometry);
					return createGeometry(subType, (List<Object>) geometry);
				})
				.toArray(Geometry[]::new);
	}

	private Geometry[] createCurves(List<Object> coordinates, GeometryFactory factory) {
		return coordinates.stream()
				.map(curve -> factory.createLineString(createCoordinateArray((List<Object>) curve)))
				.toArray(Geometry[]::new);
	}

	private Geometry[] createSurfaces(List<Object> coordinates, GeometryFactory factory) {
		return coordinates.stream()
				.map(surface -> factory.createPolygon(createLinearRing((List<Object>) ((List<Object>) surface).get(0), factory)))
				.toArray(Geometry[]::new);
	}

	private String determineGeometryType(List<Object> geometry) {
		// Simplified type determination based on coordinate structure
		if (geometry.size() == 2 && geometry.get(0) instanceof Number && geometry.get(1) instanceof Number) {
			return "Point";
		} else if (geometry.size() > 0 && geometry.get(0) instanceof List) {
			int size = ((List<?>) geometry.get(0)).size();
			if (size == 2 && ((List<?>) geometry.get(0)).get(0) instanceof Number) {
				return "LineString";
			} else if (size > 0 && ((List<?>) geometry.get(0)).get(0) instanceof List) {
				return "Polygon";
			}
		}
		throwException("Unable to determine geometry type from coordinates.");
		return null;
	}

	private String bytesToHex(byte[] bytes) {
		StringBuilder hexString = new StringBuilder();
		for (byte b : bytes) {
			hexString.append(String.format("%02X", b));
		}
		return hexString.toString();
	}
}
