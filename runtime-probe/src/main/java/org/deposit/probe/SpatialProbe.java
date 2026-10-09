package org.deposit.probe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.geotools.api.referencing.NoSuchAuthorityCodeException;
import org.geotools.referencing.CRS;
import org.locationtech.jts.io.WKTReader;
import org.neo4j.gis.spatial.EditableLayer;
import org.neo4j.gis.spatial.EditableLayerImpl;
import org.neo4j.gis.spatial.SpatialDatabaseService;
import org.neo4j.gis.spatial.WKTGeometryEncoder;
import org.neo4j.gis.spatial.index.IndexManager;
import org.neo4j.gis.spatial.index.SpatialIndexWriter;
import org.neo4j.gis.spatial.rtree.NullListener;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

/** Test-only compatibility surface. Never install in a user distribution. */
public class SpatialProbe {
    @Context public Transaction tx;
    @Context public GraphDatabaseAPI db;
    @Context public KernelTransaction kernel;

    private SpatialDatabaseService spatial() {
        return new SpatialDatabaseService(new IndexManager(db, kernel.securityContext()));
    }

    private static String layerName(String namespace, Long srid) {
        return "deposit_geometry_" + UUID.fromString(namespace) + "_"
            + (srid == null ? "unknown" : "srid_" + srid);
    }

    private EditableLayer layer(String name, Long srid) throws Exception {
        SpatialDatabaseService spatial = spatial();
        var found = spatial.getLayer(tx, name, false);
        if (found != null) return (EditableLayer) found;
        // Explicit creation avoids the registered WKT type's default WGS84 CRS.
        var created = (EditableLayer) spatial.createLayer(tx, name, WKTGeometryEncoder.class,
            EditableLayerImpl.class, null, "wkt", null, null);
        if (srid != null) {
            try { created.setCoordinateReferenceSystem(tx, CRS.decode("EPSG:" + srid, true)); }
            catch (NoSuchAuthorityCodeException unresolved) { /* preserve unknown metadata */ }
        }
        return created;
    }

    private static void fail(String requested, String point) {
        if (requested.equals(point)) throw new IllegalStateException("STAGE0_INJECTED_" + point);
    }

    @Procedure(name = "stage0.geometry.write", mode = Mode.WRITE)
    public Stream<Row> write(@Name("namespace") String namespace, @Name("id") String id,
            @Name("wkt") String wkt, @Name("srid") Long srid, @Name("failAt") String failAt) throws Exception {
        new WKTReader().read(wkt);
        String name = layerName(namespace, srid);
        // Serialize probe layer-registry operations using a test-owned lock node.
        Node lock = tx.findNode(Label.label("Stage0Lock"), "id", "registry");
        if (lock == null) throw new IllegalStateException("Seed Stage0Lock before probing");
        tx.acquireWriteLock(lock);
        EditableLayer destination = layer(name, srid);
        fail(failAt, "layer");
        Node geometry;
        try(var matches=tx.findNodes(Label.label("Stage0Geometry"),Map.of("id",id,"namespace",namespace))) {
            geometry=matches.hasNext()?matches.next():null;
            if(matches.hasNext()) throw new IllegalStateException("Duplicate probe geometry identity");
        }
        if (geometry == null) {
            geometry = tx.createNode(Label.label("Stage0Geometry"));
            geometry.setProperty("id", id);
        } else if (geometry.hasProperty("layer")) {
            String previous=(String)geometry.getProperty("layer");
            var old = previous.equals(name)?destination:(EditableLayer)spatial().getLayer(tx,previous,false);
            old.removeFromIndex(tx, geometry.getElementId());
            // Reinitialize an empty tree: the published implementation otherwise
            // carries a zero-sized origin envelope into its next insertion.
            if(old.getIndex().count(tx)==0) ((SpatialIndexWriter)old.getIndex()).clear(tx,new NullListener());
            old.finalizeTransaction(tx);
        }
        fail(failAt, "removal");
        geometry.setProperty("wkt", wkt);
        geometry.removeProperty("bbox"); // ensureIndexable must calculate bounds for the new coordinates
        geometry.setProperty("namespace", namespace);
        geometry.setProperty("layer", name);
        if (srid == null) geometry.removeProperty("srid"); else geometry.setProperty("srid", srid);
        fail(failAt, "update");
        destination.add(tx, geometry);
        fail(failAt, "registration");
        destination.finalizeTransaction(tx); // flush index metadata inside the caller's transaction
        return inspect(namespace, srid);
    }

    @Procedure(name = "stage0.geometry.inspect", mode = Mode.READ)
    public Stream<Row> inspect(@Name("namespace") String namespace, @Name("srid") Long srid) {
        var layer = spatial().getLayer(tx, layerName(namespace, srid), true);
        if (layer == null) return Stream.of(new Row(Map.of("exists", false)));
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Node node : layer.getIndex().getAllIndexedNodes(tx)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", node.getProperty("id"));
            item.put("elementId",node.getElementId());
            item.put("wkt", node.getProperty("wkt"));
            var coordinates = layer.getGeometryEncoder().decodeGeometry(node).getCoordinates();
            List<List<Double>> values = new ArrayList<>();
            for (var coordinate : coordinates) {
                values.add(Double.isNaN(coordinate.getZ()) ? List.of(coordinate.x, coordinate.y)
                    : List.of(coordinate.x, coordinate.y, coordinate.getZ()));
            }
            item.put("coordinates", values);
            item.put("bbox", node.getProperty("bbox"));
            entries.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exists", true);
        result.put("entries", entries);
        result.put("count", (long) layer.getIndex().count(tx));
        var bounds=layer.getIndex().getBoundingBox(tx);
        result.put("bounds",bounds==null?null:List.of(bounds.getMin(0),bounds.getMin(1),bounds.getMax(0),bounds.getMax(1)));
        result.put("crs", layer.getCoordinateReferenceSystem(tx) == null ? null
            : layer.getCoordinateReferenceSystem(tx).toWKT());
        return Stream.of(new Row(result));
    }

    public static class Row {
        public final Map<String, Object> data;
        public Row(Map<String, Object> data) { this.data = data; }
    }
}
