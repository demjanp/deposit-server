package deposit.repository;

import deposit.common.*;
import java.util.*;
import org.geotools.api.referencing.NoSuchAuthorityCodeException;
import org.geotools.referencing.CRS;
import org.neo4j.gis.spatial.*;
import org.neo4j.gis.spatial.index.*;
import org.neo4j.gis.spatial.rtree.NullListener;
import org.neo4j.graphdb.*;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

/** Spatial operations participate exclusively in the caller's transaction. */
public final class GeometryRepository {
    private final Transaction tx;private final CoreRepository core;private final SpatialDatabaseService spatial;
    private final Map<String,EditableLayer> layers;private final ServerContext server;
    public GeometryRepository(Transaction tx,CoreRepository core) {
        this.tx=tx;this.core=core;var internal=(InternalTransaction)tx;
        server=ServerContext.forDatabase(internal.getDatabaseId());
        spatial=new SpatialDatabaseService(new IndexManager(server.database(),internal.securityContext()));
        layers=server.state(internal.kernelTransaction()).geometryLayers;
    }
    public static String layerName(String ns,Object srid) {return "deposit_geometry_"+ns+"_"+(srid==null?"unknown":"srid_"+srid);}
    private void lock() {tx.acquireWriteLock(core.find("DepositStorageLock","id","spatial"));}
    private EditableLayer layer(String name,Object srid,boolean create) {
        lock();if(layers.containsKey(name))return layers.get(name);
        var found=spatial.getLayer(tx,name,false);
        EditableLayer layer;
        if(found==null) {
            if(!create)throw new IllegalStateException("Missing geometry layer");
            layer=(EditableLayer)spatial.createLayer(tx,name,WKTGeometryEncoder.class,EditableLayerImpl.class,null,"wkt",null,null);
            server.storagePoint("geometry-layer");
            if(srid!=null)try {layer.setCoordinateReferenceSystem(tx,CRS.decode("EPSG:"+srid,true));}catch(NoSuchAuthorityCodeException e) {}catch(Exception e){throw new IllegalStateException("Cannot resolve geometry CRS",e);}
        } else {
            layer=(EditableLayer)found;
            if(!(layer.getGeometryEncoder() instanceof WKTGeometryEncoder encoder)||!"wkt:bbox".equals(encoder.getConfiguration()))throw new IllegalStateException("Conflicting geometry encoder");
            org.geotools.api.referencing.crs.CoordinateReferenceSystem expected=null;
            if(srid!=null)try{expected=CRS.decode("EPSG:"+srid,true);}catch(NoSuchAuthorityCodeException e){}catch(Exception e){throw new IllegalStateException(e);}
            var actual=layer.getCoordinateReferenceSystem(tx);
            if(expected==null?actual!=null:actual==null||!CRS.equalsIgnoreMetadata(expected,actual))throw new IllegalStateException("Conflicting geometry CRS");
        }
        layers.put(name,layer);return layer;
    }
    public Node geometry(Node descriptor) {var edge=descriptor.getSingleRelationship(RelationshipType.withName("HAS_GEOMETRY"),Direction.OUTGOING);return edge==null?null:edge.getEndNode();}
    public Map<String,Object> value(Node node) {return Json.map("wkt",node.getProperty("wkt"),"srid",node.getProperty("srid",null),"verticalSrid",node.getProperty("verticalSrid",null),"axisOrder",node.getProperty("axisOrder"),"dimensions",node.getProperty("dimensions"));}
    private EditableLayer verifyIndex(Node node) {
        var layer=layer(layerName((String)node.getProperty("namespaceId"),node.getProperty("srid",null)),node.getProperty("srid",null),false);
        long count=0;for(Node entry:layer.getIndex().getAllIndexedNodes(tx))if(entry.equals(node))count++;
        if(count!=1)throw new IllegalStateException("Invalid geometry membership");
        try {
            var bounds=new org.locationtech.jts.io.WKTReader().read((String)node.getProperty("wkt")).getEnvelopeInternal();
            double[] expected={bounds.getMinX(),bounds.getMinY(),bounds.getMaxX(),bounds.getMaxY()};
            if(!Arrays.equals(expected,(double[])node.getProperty("bbox")))throw new IllegalStateException("Stale geometry bounds");
        }catch(org.locationtech.jts.io.ParseException e){throw new IllegalStateException(e);}return layer;
    }
    private void removeIndex(Node node) {
        var layer=verifyIndex(node);
        layer.removeFromIndex(tx,node.getElementId());
        if(layer.getIndex().count(tx)==0)((SpatialIndexWriter)layer.getIndex()).clear(tx,new NullListener());
        layer.finalizeTransaction(tx);
        server.storagePoint("geometry-removal");
    }
    public boolean write(Node descriptor,Object normalized,String actor) {
        Node node=geometry(descriptor);if(node!=null&&Objects.equals(value(node),normalized)){verifyIndex(node);return false;}
        if(node!=null)removeIndex(node);
        if(normalized==null) {if(node==null)return false;descriptor.getSingleRelationship(RelationshipType.withName("HAS_GEOMETRY"),Direction.OUTGOING).delete();node.delete();return true;}
        var data=ValueCodec.map(normalized);
        if(node==null) {node=core.create("DGeometry",(String)descriptor.getProperty("namespaceId"),actor,Map.of());descriptor.createRelationshipTo(node,RelationshipType.withName("HAS_GEOMETRY"));}
        core.update(node,data,actor);node.setProperty("geometryType",GeometryCodec.fromWkt(data.get("wkt"),data.get("srid"),data.get("verticalSrid")).get("geometryType"));node.removeProperty("bbox");server.storagePoint("geometry-update");
        var layer=layer(layerName((String)descriptor.getProperty("namespaceId"),data.get("srid")),data.get("srid"),true);
        layer.add(tx,node);server.storagePoint("geometry-registration");layer.finalizeTransaction(tx);return true;
    }
    public void deleteLayers(String namespace) {
        lock();for(String name:spatial.getLayerNames(tx))if(name.startsWith("deposit_geometry_"+namespace+"_")) {spatial.deleteLayer(tx,name,new NullListener());layers.remove(name);}
    }
    public void rebuild(String namespace) {
        core.lockNamespace(namespace);
        for(Node node:core.nodes("DGeometry",Map.of("namespaceId",namespace)))removeIndex(node);
        deleteLayers(namespace);
        for(Node node:core.nodes("DGeometry",Map.of("namespaceId",namespace))) {
            GeometryCodec.normalize(value(node));node.removeProperty("bbox");var layer=layer(layerName(namespace,node.getProperty("srid",null)),node.getProperty("srid",null),true);layer.add(tx,node);layer.finalizeTransaction(tx);
        }
    }
}
