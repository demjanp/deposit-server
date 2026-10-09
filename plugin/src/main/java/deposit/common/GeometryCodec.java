package deposit.common;

import java.util.*;
import java.util.regex.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.*;
import org.locationtech.jts.operation.valid.IsValidOp;

/** Pure validation and lossless canonical encoding; never touches storage. */
public final class GeometryCodec {
    private static final Set<String> TYPES=Set.of("Point","LineString","Polygon","MultiPoint","MultiLineString","MultiPolygon");
    private GeometryCodec() {}
    public static Map<String,Object> normalize(Object value) {
        var m=ValueCodec.map(value);
        if(!Set.of("wkt","srid","verticalSrid","axisOrder","dimensions").containsAll(m.keySet()))invalid();
        var result=fromWkt(m.get("wkt"),m.get("srid"),m.get("verticalSrid"));
        if(!Objects.equals(result.get("axisOrder"),m.get("axisOrder"))||!(m.get("dimensions") instanceof Number n)||n.doubleValue()!=((Number)result.get("dimensions")).doubleValue())invalid();
        result.remove("geometryType");result.remove("coordinates");return result;
    }
    public static Map<String,Object> fromWkt(Object input,Object srid,Object vertical) {
        try {
            if(!(input instanceof String))invalid();String wkt=((String)input).strip();
            Matcher header=Pattern.compile("(?is)^([a-z]+)\\s*(Z)?\\s*(\\(.*\\))$").matcher(wkt);
            if(!header.matches())invalid();
            int dimensions=header.group(2)==null?2:3,depth=0;
            String body=header.group(3);
            for(int i=0;i<body.length();i++) {char ch=body.charAt(i);if(ch=='(')depth++;if(ch==')')depth--;if(depth<0||(depth==0&&i<body.length()-1))invalid();}
            if(depth!=0)invalid();
            WKTReader reader=new WKTReader();reader.setIsOldJtsCoordinateSyntaxAllowed(false);
            Geometry g=reader.read(wkt);
            validate(g,dimensions);
            WKTWriter writer=new WKTWriter(dimensions);
            return Json.map("geometryType",g.getGeometryType(),"coordinates",coordinates(g,dimensions),"wkt",writer.write(g),"srid",srid(srid),"verticalSrid",srid(vertical),"axisOrder",dimensions==3?"xyz":"xy","dimensions",(long)dimensions);
        } catch(ExceptionHandler.DepositException e) {throw e;} catch(Exception e) {throw ExceptionHandler.fail("VALIDATION_FAILED","Invalid geometry");}
    }
    private static Object srid(Object value) {if(value==null)return null;if(!(value instanceof Long||value instanceof Integer))invalid();return ((Number)value).longValue();}
    public static Map<String,Object> fromCoordinates(Object type,Object coordinates,Object srid,Object vertical) {
        if(!(type instanceof String t)||!TYPES.contains(t))invalid();
        String t=(String)type;int rank=switch(t) {case "Point"->0;case "LineString","MultiPoint"->1;case "Polygon","MultiLineString"->2;default->3;};
        int[] dimension={0};String body=coordinateText(coordinates,rank,dimension);
        return fromWkt(t.toUpperCase(Locale.ROOT)+(dimension[0]==3?" Z ":" ")+(rank==0?"("+body+")":body),srid,vertical);
    }
    private static String coordinateText(Object value,int rank,int[] dimension) {
        if(!(value instanceof List<?>))invalid();List<?> values=(List<?>)value;if(values.isEmpty())invalid();
        var parts=new ArrayList<String>();
        if(rank==0) {
            if(values.size()!=2&&values.size()!=3)invalid();if(dimension[0]==0)dimension[0]=values.size();if(dimension[0]!=values.size())invalid();
            for(Object v:values) {if(!(v instanceof Number n)||!Double.isFinite(n.doubleValue()))invalid();parts.add(Double.toString(((Number)v).doubleValue()));}
            return String.join(" ",parts);
        }
        for(Object item:values)parts.add(coordinateText(item,rank-1,dimension));return "("+String.join(", ",parts)+")";
    }
    private static void validate(Geometry g,int dimensions) {
        if(!TYPES.contains(g.getGeometryType())||g.isEmpty()||!new IsValidOp(g).isValid())invalid();
        for(Coordinate p:g.getCoordinates())if(!Double.isFinite(p.x)||!Double.isFinite(p.y)||(dimensions==3?!Double.isFinite(p.getZ()):!Double.isNaN(p.getZ())))invalid();
        if(g instanceof GeometryCollection)for(int i=0;i<g.getNumGeometries();i++)validate(g.getGeometryN(i),dimensions);
    }
    private static List<Double> coordinate(Coordinate c,int d) {return d==3?List.of(c.x,c.y,c.getZ()):List.of(c.x,c.y);}
    private static Object coordinates(Geometry g,int d) {
        if(g instanceof Point p)return coordinate(p.getCoordinate(),d);
        if(g instanceof LineString l)return Arrays.stream(l.getCoordinates()).map(c->coordinate(c,d)).toList();
        var result=new ArrayList<Object>();
        if(g instanceof Polygon p) {result.add(coordinates(p.getExteriorRing(),d));for(int i=0;i<p.getNumInteriorRing();i++)result.add(coordinates(p.getInteriorRingN(i),d));}
        else for(int i=0;i<g.getNumGeometries();i++)result.add(coordinates(g.getGeometryN(i),d));
        return result;
    }
    private static void invalid() {throw ExceptionHandler.fail("VALIDATION_FAILED","Invalid or unsupported geometry");}
}
