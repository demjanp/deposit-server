package deposit.common;

import java.util.*;
import java.time.*;
import java.time.temporal.*;
import org.neo4j.graphdb.spatial.Point;
import org.neo4j.values.storable.*;

/** Encodes declaration-directed typed values, including geometry and resources. */
public final class ValueCodec {
    public static final Set<String> TYPES=Set.of("String","Integer","Float","Boolean","Date","Time","LocalTime","DateTime","LocalDateTime","Duration","Point","List","DDict","DGeometry","DResource");
    private ValueCodec() {}
    public static String type(Object type) {
        if(!(type instanceof String s) || !TYPES.contains(s)) throw ExceptionHandler.fail("VALIDATION_FAILED","Unsupported descriptor type");
        return s;
    }
    public static Object normalize(String type,Object value) {return normalize(type,value,0,false);}
    /** Normalizes a typed value whose nested collection payload came from JSON text. */
    public static Object normalizeJson(String type,Object value) {return normalize(type,value,0,true);}
    private static Object normalize(String type,Object value,int depth,boolean json) {
        type(type);
        if(depth>0 && Set.of("DGeometry","DResource").contains(type))throw ExceptionHandler.fail("VALIDATION_FAILED","Geometry/resources cannot occur in collections");
        if(value==null) return null;
        try {
            return switch(type) {
                case "DGeometry" -> GeometryCodec.normalize(value);
                case "DResource" -> {var m=map(value);keys(m,Set.of("resourceId"));yield Json.map("resourceId",RequestContext.uuid(m.get("resourceId")));}
                case "String" -> {if(!(value instanceof String)) throw new IllegalArgumentException();yield value;}
                case "Boolean" -> {if(!(value instanceof Boolean)) throw new IllegalArgumentException();yield value;}
                case "Integer" -> {
                    if(json && value instanceof String s) yield Long.parseLong(s);
                    if(!(value instanceof Long || value instanceof Integer)) throw new IllegalArgumentException();
                    yield ((Number)value).longValue();
                }
                case "Float" -> {
                    if(!(value instanceof Number n)) throw new IllegalArgumentException();
                    double d=n.doubleValue();if(!Double.isFinite(d)) throw new IllegalArgumentException();
                    if(n instanceof Long || n instanceof Integer) {
                        if(java.math.BigDecimal.valueOf(n.longValue()).compareTo(new java.math.BigDecimal(d))!=0) throw new IllegalArgumentException();
                    }
                    yield d;
                }
                case "Date" -> LocalDate.parse((String)value).toString();
                case "Time" -> OffsetTime.parse((String)value).toString();
                case "LocalTime" -> LocalTime.parse((String)value).toString();
                case "DateTime" -> {String s=(String)value;yield s.contains("[")?ZonedDateTime.parse(s).toString():OffsetDateTime.parse(s).toString();}
                case "LocalDateTime" -> LocalDateTime.parse((String)value).toString();
                case "Duration" -> {
                    var m=map(value);keys(m,Set.of("months","days","seconds","nanoseconds"));
                    var duration=DurationValue.duration(integer(m.get("months"),json),integer(m.get("days"),json),integer(m.get("seconds"),json),integer(m.get("nanoseconds"),json));
                    yield durationMap(duration);
                }
                case "Point" -> {
                    var m=map(value);keys(m,Set.of("srid","coordinates"));
                    long srid=integer(m.get("srid"),json);var crs=CoordinateReferenceSystem.get(Math.toIntExact(srid));
                    if(!(m.get("coordinates") instanceof List<?> list) || list.size()!=crs.getDimension()) throw new IllegalArgumentException();
                    double[] coordinates=new double[list.size()];for(int i=0;i<list.size();i++) coordinates[i]=(Double)normalize("Float",list.get(i),depth+1,json);
                    var point=Values.pointValue(crs,coordinates);yield pointMap(point);
                }
                case "List" -> {
                    var m=map(value);keys(m,Set.of("itemType","items"));String itemType=type(m.get("itemType"));
                    if(Set.of("DGeometry","DResource").contains(itemType))throw ExceptionHandler.fail("VALIDATION_FAILED","Geometry/resources cannot occur in collections");
                    if(!(m.get("items") instanceof List<?> items)) throw new IllegalArgumentException();
                    var result=new ArrayList<Object>();for(Object item:items) result.add(normalize(itemType,item,depth+1,json));
                    yield Json.map("itemType",itemType,"items",result);
                }
                case "DDict" -> {
                    var result=new TreeMap<String,Object>();
                    for(var entry:map(value).entrySet()) {var m=map(entry.getValue());keys(m,Set.of("type","value"));String entryType=type(m.get("type"));result.put(entry.getKey(),Json.map("type",entryType,"value",normalize(entryType,m.get("value"),depth+1,json)));}
                    yield result;
                }
                default -> throw new IllegalArgumentException();
            };
        } catch(ExceptionHandler.DepositException e) {throw e;}
        catch(RuntimeException e) {throw ExceptionHandler.fail("TYPE_CONFLICT","Value does not match "+type);}
    }
    public static Object storage(String type,Object normalized) {
        if(normalized==null) return null;
        return switch(type) {
            case "List","DDict" -> Json.write(jsonValue(type,normalized));
            case "Date" -> LocalDate.parse((String)normalized);
            case "Time" -> OffsetTime.parse((String)normalized);
            case "LocalTime" -> LocalTime.parse((String)normalized);
            case "DateTime" -> ((String)normalized).contains("[")?ZonedDateTime.parse((String)normalized):OffsetDateTime.parse((String)normalized);
            case "LocalDateTime" -> LocalDateTime.parse((String)normalized);
            case "Duration" -> {var m=map(normalized);yield DurationValue.duration(integer(m.get("months"),false),integer(m.get("days"),false),integer(m.get("seconds"),false),integer(m.get("nanoseconds"),false));}
            case "Point" -> {var m=map(normalized);var points=(List<?>)m.get("coordinates");yield Values.pointValue(CoordinateReferenceSystem.get(Math.toIntExact((Long)m.get("srid"))),points.stream().mapToDouble(x->((Number)x).doubleValue()).toArray());}
            default -> normalized;
        };
    }
    public static Object decode(String type,Object stored) {
        if(stored==null) return null;
        return switch(type) {
            case "List","DDict" -> normalize(type,Json.read((String)stored),0,true);
            case "Date","Time","LocalTime","DateTime","LocalDateTime" -> stored.toString();
            case "Duration" -> durationMap((TemporalAmount)stored);
            case "Point" -> pointMap((Point)stored);
            default -> stored;
        };
    }
    private static Object jsonValue(String type,Object value) {
        if(value==null) return null;
        if(type.equals("Integer")) return value.toString();
        if(type.equals("List")) {var m=map(value);String t=(String)m.get("itemType");var items=new ArrayList<Object>();for(Object v:(List<?>)m.get("items")) items.add(jsonValue(t,v));return Json.map("itemType",t,"items",items);}
        if(type.equals("DDict")) {var result=new TreeMap<String,Object>();for(var e:map(value).entrySet()) {var m=map(e.getValue());String t=(String)m.get("type");result.put(e.getKey(),Json.map("type",t,"value",jsonValue(t,m.get("value"))));}return result;}
        return value;
    }
    public static String jsonText(String type,Object value) {return Json.write(jsonValue(type,normalize(type,value)));}
    private static long integer(Object v,boolean json) {return (Long)normalize("Integer",v,0,json);}
    public static Map<String,Object> durationMap(TemporalAmount d) {return Json.map("months",d.get(ChronoUnit.MONTHS),"days",d.get(ChronoUnit.DAYS),"seconds",d.get(ChronoUnit.SECONDS),"nanoseconds",d.get(ChronoUnit.NANOS));}
    public static Map<String,Object> pointMap(Point point) {return Json.map("srid",(long)point.getCRS().getCode(),"coordinates",Arrays.stream(point.getCoordinate().getCoordinate()).boxed().toList());}
    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object value) {
        if(!(value instanceof Map<?,?> m)||m.keySet().stream().anyMatch(k->!(k instanceof String))) throw ExceptionHandler.fail("VALIDATION_FAILED","Expected a map with String keys");
        return (Map<String,Object>)m;
    }
    private static void keys(Map<String,Object> m,Set<String> keys) {if(!m.keySet().equals(keys)) throw new IllegalArgumentException();}
}
