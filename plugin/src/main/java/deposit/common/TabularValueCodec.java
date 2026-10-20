package deposit.common;

import java.time.*;
import java.time.format.*;
import java.util.*;

/** Value conversion and text representation shared by tabular format adapters. */
public final class TabularValueCodec {
    private TabularValueCodec() {}

    public static Object parseCell(String type,String cell,String decimal,Object format){
        if(type.equals("String"))return cell;
        String value=cell;if(decimal.equals(",")&&(type.equals("Float")))value=value.replace(',','.');
        if(format!=null){DateTimeFormatter formatter=DateTimeFormatter.ofPattern(Objects.toString(format),Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);return switch(type){case "Date"->LocalDate.parse(cell,formatter).toString();case "Time"->OffsetTime.parse(cell,formatter).toString();case "LocalTime"->LocalTime.parse(cell,formatter).toString();case "DateTime"->OffsetDateTime.parse(cell,formatter).toString();case "LocalDateTime"->LocalDateTime.parse(cell,formatter).toString();default->throw new IllegalArgumentException();};}
        return switch(type){case "Integer"->Long.parseLong(value);case "Float"->{double d=Double.parseDouble(value);if(!Double.isFinite(d))throw new IllegalArgumentException();yield d;}case "Boolean"->{if(!Set.of("true","false").contains(value))throw new IllegalArgumentException();yield Boolean.valueOf(value);}case "Date"->LocalDate.parse(value).toString();case "Time"->OffsetTime.parse(value).toString();case "LocalTime"->LocalTime.parse(value).toString();case "DateTime"->value.contains("[")?ZonedDateTime.parse(value).toString():OffsetDateTime.parse(value).toString();case "LocalDateTime"->LocalDateTime.parse(value).toString();case "List","DDict"->Json.read(value);case "DGeometry"->parseGeometryValue(value,null,null);case "Point"->value;default->throw new IllegalArgumentException("Unsupported CSV type");};
    }

    public static Map<String,Object> parseGeometryValue(String text,Object explicitSrid,Object explicitVertical){
        String value=text;Long srid=explicitSrid instanceof Number n?n.longValue():null,vertical=explicitVertical instanceof Number n?n.longValue():null;
        java.util.regex.Matcher h=java.util.regex.Pattern.compile("^SRID=(-?\\d+);").matcher(value);if(h.find()){long parsed=Long.parseLong(h.group(1));if(srid!=null&&srid!=parsed)throw new IllegalArgumentException("SRID conflicts with mapping");srid=parsed;value=value.substring(h.end());}
        java.util.regex.Matcher v=java.util.regex.Pattern.compile("^VERT_SRID=(-?\\d+);").matcher(value);if(v.find()){long parsed=Long.parseLong(v.group(1));if(vertical!=null&&vertical!=parsed)throw new IllegalArgumentException("vertical SRID conflicts with mapping");vertical=parsed;value=value.substring(v.end());}
        value=value.replaceFirst("(?i)^([A-Z]+)Z(?=\\s*\\()","$1 Z");Map<String,Object> parsed=GeometryCodec.fromWkt(value,srid,vertical);
        return Json.map("geometryType",parsed.get("geometryType"),"coordinates",parsed.get("coordinates"),"wkt",parsed.get("wkt"),"srid",parsed.get("srid"),"verticalSrid",parsed.get("verticalSrid"),"axisOrder",parsed.get("axisOrder"),"dimensions",parsed.get("dimensions"));
    }

    public static String exportText(String type,Object value){
        if(value==null)return "";
        return switch(type){case "List","DDict"->ValueCodec.jsonText(type,value instanceof String s?Json.read(s):value);case "Duration"->ValueCodec.storage("Duration",ValueCodec.normalize("Duration",value)).toString();case "Point"->{Map<String,Object> point=ValueCodec.map(value);List<?> coords=(List<?>)point.get("coordinates");yield "POINT("+String.join(" ",coords.stream().map(Object::toString).toList())+")";}case "DGeometry"->{Map<String,Object> geometry=ValueCodec.map(value);String wkt=((String)geometry.get("wkt")).toUpperCase(Locale.ROOT).replaceFirst("^([A-Z]+) Z ","$1Z ");StringBuilder text=new StringBuilder();Object srid=geometry.get("srid"),vertical=geometry.get("verticalSrid");if(srid instanceof Number n&&n.longValue()>=0)text.append("SRID=").append(n.longValue()).append(';');if(vertical instanceof Number n&&n.longValue()>=0)text.append("VERT_SRID=").append(n.longValue()).append(';');yield text.append(wkt).toString();}default->value.toString();};
    }
}
