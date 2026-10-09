package deposit.common;

import java.util.*;

/** Canonical scientific images; historical schema is resolved only from reconstructed images. */
public final class HistoryCodec {
    private HistoryCodec() {}
    public static final Set<String> KINDS=Set.of("Namespace","Class","Object","ClassDescriptor","Descriptor","Membership","Relationship","ClassRelationship","UserTool","DGeometry");
    private static final Set<String> AUDIT=Set.of("createdAt","createdBy","updatedAt","updatedBy");
    public static <T> Set<T> union(Collection<T> a,Collection<T> b){var out=new LinkedHashSet<T>(a);out.addAll(b);return out;}
    public static Map<String,Map<String,Object>> copy(Map<String,Map<String,Object>> state){var out=new TreeMap<String,Map<String,Object>>();state.forEach((id,m)->out.put(id,Json.read(Json.write(m))));return out;}
    public static Map<String,Object> scientific(Map<String,Object> image){if(image==null)return null;var out=new TreeMap<>(image);AUDIT.forEach(out::remove);return out;}
    public static boolean equal(Object a,Object b){return Objects.equals(Json.write(a),Json.write(b));}
    public static Set<String> fields(Map<String,Object> a,Map<String,Object> b){
        if(equal(scientific(a),scientific(b)))return Set.of();
        if(a==null||b==null)return Set.of("*");
        var fields=new TreeSet<String>();for(String key:union(a.keySet(),b.keySet()))if(!AUDIT.contains(key)&&!equal(a.get(key),b.get(key)))fields.add(key);
        String kind=(String)b.get("kind");
        if(Set.of("Membership","DGeometry").contains(kind)||kind.equals("Class")&&fields.contains("name")||kind.equals("ClassDescriptor")&&(fields.contains("name")||fields.contains("type"))||fields.stream().anyMatch(Set.of("sourceId","targetId","objectId","classId","descriptorId","geometryId")::contains)||fields.contains("value")&&(a.get("value") instanceof Map||a.get("value") instanceof List||b.get("value") instanceof Map||b.get("value") instanceof List))return Set.of("*");
        return fields;
    }
    public static Set<String> references(Map<String,Object> image){var out=new TreeSet<String>();if(image!=null)for(String key:List.of("objectId","classId","sourceId","targetId","descriptorId","geometryId"))if(image.get(key) instanceof String id)out.add(id);return out;}
    public static String descriptorType(Map<String,Map<String,Object>> state,Map<String,Object> descriptor){
        String object=(String)descriptor.get("objectId");String clazz=null;
        for(var image:state.values())if("Membership".equals(image.get("kind"))&&object.equals(image.get("objectId"))){if(clazz!=null)invalid("Multiple memberships");clazz=(String)image.get("classId");}
        if(clazz==null)invalid("Missing historical membership");
        for(var image:state.values())if("ClassDescriptor".equals(image.get("kind"))&&clazz.equals(image.get("classId"))&&Objects.equals(descriptor.get("name"),image.get("name")))return ValueCodec.type(image.get("type"));
        invalid("Missing historical declaration");return null;
    }
    public static Set<String> schemaReferences(Map<String,Map<String,Object>> state,Map<String,Object> image){
        var ids=new TreeSet<String>();if(image==null||!"Descriptor".equals(image.get("kind")))return ids;
        for(var m:state.values())if("Membership".equals(m.get("kind"))&&Objects.equals(image.get("objectId"),m.get("objectId"))){ids.add((String)m.get("id"));ids.add((String)m.get("classId"));for(var d:state.values())if("ClassDescriptor".equals(d.get("kind"))&&Objects.equals(m.get("classId"),d.get("classId"))&&Objects.equals(image.get("name"),d.get("name")))ids.add((String)d.get("id"));}return ids;
    }
    private static void requires(Map<String,Map<String,Object>> state,Object id,String kind){if(!(id instanceof String)||!state.containsKey(id)||!kind.equals(state.get(id).get("kind")))invalid("Invalid historical endpoint category");}
    private static void shape(Map<String,Object> m,String kind){
        var allowed=new HashSet<>(List.of("kind","id","namespaceId","createdAt","createdBy","updatedAt","updatedBy"));
        allowed.addAll(switch(kind){case "Namespace"->List.of("name","description");case "Class"->List.of("name","position","order","comment");case "Object"->List.of("position");case "ClassDescriptor"->List.of("classId","name","type","order","comment");case "Descriptor"->List.of("objectId","name","value","geometryId");case "Membership"->List.of("objectId","classId");case "Relationship"->List.of("sourceId","targetId","label","weight");case "ClassRelationship"->List.of("sourceId","targetId","label","comment");case "UserTool"->List.of("name","data","comment");case "DGeometry"->List.of("descriptorId","wkt","geometryType","srid","verticalSrid","axisOrder","dimensions");default->List.of();});
        if(!allowed.containsAll(m.keySet()))invalid("Noncanonical historical image fields");
        for(String key:List.of("name","description","comment","label","data"))if(m.containsKey(key)&&!(m.get(key) instanceof String))invalid("Invalid historical text");
        if(m.containsKey("order")&&!(m.get("order") instanceof Long||m.get("order") instanceof Integer))invalid("Invalid historical order");
        if(m.containsKey("position")){if(!(m.get("position") instanceof List<?> list)||list.size()!=2)invalid("Invalid historical position");for(Object x:(List<?>)m.get("position"))if(!(x instanceof Number n)||!Double.isFinite(n.doubleValue()))invalid("Invalid historical position");}
        if(kind.equals("Descriptor")&&!m.containsKey("value"))invalid("Missing historical value");
        if(m.containsKey("weight"))ValueCodec.normalize("Float",m.get("weight"));
    }
    public static void validate(Map<String,Map<String,Object>> state){
        var roots=state.values().stream().filter(m->"Namespace".equals(m.get("kind"))).toList();if(roots.size()!=1)invalid("Historical state requires one Namespace");Object namespace=roots.getFirst().get("id");
        var names=new HashSet<String>();var memberships=new HashMap<String,String>();var geometryOwners=new HashSet<String>();
        for(var entry:state.entrySet()) {
            var m=entry.getValue();String kind=(String)m.get("kind");if(!KINDS.contains(kind)||!entry.getKey().equals(m.get("id")))invalid("Invalid historical image identity");
            shape(m,kind);
            if(!Objects.equals(namespace,m.get("namespaceId")))invalid("Historical image escaped Namespace");
            if(m.containsKey("name")){Object name=m.get("name");if(!(name instanceof String s)||s.isBlank()||s.codePointCount(0,s.length())>255||s.codePoints().anyMatch(Character::isISOControl))invalid("Invalid historical name");
                String owner=Objects.toString(m.get("objectId"),Objects.toString(m.get("classId"),""));if(!names.add(kind+"/"+owner+"/"+name))invalid("Duplicate historical name");}
            switch(kind){
                case "Membership"->{requires(state,m.get("objectId"),"Object");requires(state,m.get("classId"),"Class");if(memberships.put((String)m.get("objectId"),(String)m.get("classId"))!=null)invalid("Multiple historical memberships");}
                case "ClassDescriptor"->{requires(state,m.get("classId"),"Class");ValueCodec.type(m.get("type"));}
                case "Descriptor"->{requires(state,m.get("objectId"),"Object");if(m.containsKey("type"))invalid("Descriptor image duplicates type");String type=descriptorType(state,m);if(!equal(ValueCodec.normalize(type,m.get("value")),m.get("value")))invalid("Noncanonical historical typed value");
                    if("DGeometry".equals(type)&&m.get("value")!=null){requires(state,m.get("geometryId"),"DGeometry");var g=state.get(m.get("geometryId"));if(!m.get("id").equals(g.get("descriptorId"))||!equal(m.get("value"),geometryValue(g)))invalid("Historical geometry ownership/value mismatch");}
                    else if(m.get("geometryId")!=null)invalid("Unexpected geometry identity");}
                case "DGeometry"->{requires(state,m.get("descriptorId"),"Descriptor");if(!geometryOwners.add((String)m.get("descriptorId")))invalid("Multiple historical geometries");var d=state.get(m.get("descriptorId"));if(!m.get("id").equals(d.get("geometryId"))||d.get("value")==null)invalid("Orphan historical geometry");GeometryCodec.normalize(geometryValue(m));if(!Objects.equals(m.get("geometryType"),GeometryCodec.fromWkt(m.get("wkt"),m.get("srid"),m.get("verticalSrid")).get("geometryType")))invalid("Inconsistent historical geometry type");}
                case "Relationship","ClassRelationship"->{String endpoint=kind.equals("Relationship")?"Object":"Class";requires(state,m.get("sourceId"),endpoint);requires(state,m.get("targetId"),endpoint);if(!(m.get("label") instanceof String s)||s.isBlank())invalid("Invalid relationship label");if(!names.add(kind+"/"+m.get("sourceId")+"/"+m.get("targetId")+"/"+m.get("label")))invalid("Duplicate historical edge");}
                case "UserTool"->{if(!(m.get("data") instanceof String))invalid("Invalid opaque UserTool data");}
                default->{}
            }
        }
        for(var m:state.values())if("Object".equals(m.get("kind"))&&!memberships.containsKey(m.get("id")))invalid("Object without membership");
        for(var m:state.values())if("Relationship".equals(m.get("kind"))) {
            boolean found=state.values().stream().anyMatch(r->"ClassRelationship".equals(r.get("kind"))&&Objects.equals(r.get("sourceId"),memberships.get(m.get("sourceId")))&&Objects.equals(r.get("targetId"),memberships.get(m.get("targetId")))&&Objects.equals(r.get("label"),m.get("label")));
            if(!found)invalid("Missing historical Class relationship");
        }
    }
    public static Map<String,Object> geometryValue(Map<String,Object> m){return Json.map("wkt",m.get("wkt"),"srid",m.get("srid"),"verticalSrid",m.get("verticalSrid"),"axisOrder",m.get("axisOrder"),"dimensions",m.get("dimensions"));}
    public static void invalid(String message){throw ExceptionHandler.fail("REVISION_CONFLICT",message);}
}
