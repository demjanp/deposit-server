package deposit.service;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.WKTReader;

import java.util.ArrayList;
import java.util.List;

public class UtilsWktToCoordinatesService extends BaseService {

	private static UtilsWktToCoordinatesService instance;

	private UtilsWktToCoordinatesService() {}

	public static synchronized UtilsWktToCoordinatesService getInstance() {
		if (instance == null) {
			instance = new UtilsWktToCoordinatesService();
		}
		return instance;
	}

	public List<Object> utilsWktToCoordinates(String wkt) {
		try {
			WKTReader reader = new WKTReader();
			Geometry geometry = reader.read(wkt);

			return extractCoordinates(geometry);
		} catch (Exception e) {
			throwException("An error occurred while converting WKT to coordinates.", e);
			return null;
		}
	}

	private List<Object> extractCoordinates(Geometry geometry) {
		if (geometry instanceof Point) {
			return extractPointCoordinates((Point) geometry);
		} else if (geometry instanceof LineString) {
			return extractLineStringCoordinates((LineString) geometry);
		} else if (geometry instanceof Polygon) {
			return extractPolygonCoordinates((Polygon) geometry);
		} else if (geometry instanceof MultiPoint) {
			return extractMultiPointCoordinates((MultiPoint) geometry);
		} else if (geometry instanceof MultiLineString) {
			return extractMultiLineStringCoordinates((MultiLineString) geometry);
		} else if (geometry instanceof MultiPolygon) {
			return extractMultiPolygonCoordinates((MultiPolygon) geometry);
		} else if (geometry instanceof GeometryCollection) {
			return extractGeometryCollectionCoordinates((GeometryCollection) geometry);
		} else {
			throwException("Unsupported or unimplemented geometry type: " + geometry.getGeometryType());
			return null; // Unreachable but required by the compiler
		}
	}

	private List<Object> extractPointCoordinates(Point point) {
		List<Object> coordinates = new ArrayList<>();
		coordinates.add(point.getX());
		coordinates.add(point.getY());
		return coordinates;
	}

	private List<Object> extractLineStringCoordinates(LineString lineString) {
		List<Object> coordinates = new ArrayList<>();
		for (Coordinate coord : lineString.getCoordinates()) {
			coordinates.add(List.of(coord.x, coord.y));
		}
		return coordinates;
	}

	private List<Object> extractPolygonCoordinates(Polygon polygon) {
		List<Object> coordinates = new ArrayList<>();
		coordinates.add(extractLineStringCoordinates(polygon.getExteriorRing()));
		for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
			coordinates.add(extractLineStringCoordinates(polygon.getInteriorRingN(i)));
		}
		return coordinates;
	}

	private List<Object> extractMultiPointCoordinates(MultiPoint multiPoint) {
		List<Object> coordinates = new ArrayList<>();
		for (int i = 0; i < multiPoint.getNumGeometries(); i++) {
			coordinates.add(extractPointCoordinates((Point) multiPoint.getGeometryN(i)));
		}
		return coordinates;
	}

	private List<Object> extractMultiLineStringCoordinates(MultiLineString multiLineString) {
		List<Object> coordinates = new ArrayList<>();
		for (int i = 0; i < multiLineString.getNumGeometries(); i++) {
			coordinates.add(extractLineStringCoordinates((LineString) multiLineString.getGeometryN(i)));
		}
		return coordinates;
	}

	private List<Object> extractMultiPolygonCoordinates(MultiPolygon multiPolygon) {
		List<Object> coordinates = new ArrayList<>();
		for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
			coordinates.add(extractPolygonCoordinates((Polygon) multiPolygon.getGeometryN(i)));
		}
		return coordinates;
	}

	private List<Object> extractGeometryCollectionCoordinates(GeometryCollection geometryCollection) {
		List<Object> coordinates = new ArrayList<>();
		for (int i = 0; i < geometryCollection.getNumGeometries(); i++) {
			coordinates.add(extractCoordinates(geometryCollection.getGeometryN(i)));
		}
		return coordinates;
	}
}
