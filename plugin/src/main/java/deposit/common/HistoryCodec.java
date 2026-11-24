package deposit.common;

import java.util.*;

/** Canonical scientific images; historical schema is resolved only from reconstructed images. */
public final class HistoryCodec {
    private HistoryCodec() {}
    public static final Set<String> KINDS=Set.of("Namespace","Class","Object","ClassDescriptor","Descriptor","Membership","Relationship","ClassRelationship","Pattern","UserTool","DGeometry");
    private static final Set<String> AUDIT=Set.of("createdAt","createdBy","updatedAt","updatedBy");
    public static <T> Set<T> union(Collection<T> a,Collection<T> b){var out=new LinkedHashSet<T>(a);out.addAll(b);return out;}
    public static String key(String kind,String id){return kind+"\u0000"+id;}
    public static String key(Map<String,Object> image){return key((String)image.get("kind"),(String)image.get("id"));}
    public static String kindFromKey(String key){int i=key.indexOf('\u0000');if(i<0)invalid("Invalid typed history identity");return key.substring(0,i);}
    public static String idFromKey(String key){int i=key.indexOf('\u0000');if(i<0)invalid("Invalid typed history identity");return key.substring(i+1);}
    public static Map<String,Object> find(Map<String,Map<String,Object>> state,Object id,String kind){return id instanceof String s?state.get(key(kind,s)):null;}
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
    public static Set<String> references(Map<String,Object> image){var out=new TreeSet<String>();if(image==null)return out;String kind=(String)image.get("kind");Map<String,String> kinds=switch(kind){case "ClassDescriptor"->Map.of("classId","Class");case "Descriptor"->Map.of("objectId","Object","geometryId","DGeometry");case "Membership"->Map.of("objectId","Object","classId","Class");case "Relationship"->Map.of("sourceId","Object","targetId","Object");case "ClassRelationship"->Map.of("sourceId","Class","targetId","Class");case "DGeometry"->Map.of("descriptorId","Descriptor");default->Map.of();};for(var e:kinds.entrySet())if(image.get(e.getKey()) instanceof String id)out.add(key(e.getValue(),id));return out;}
    public interface DeclarationState {String descriptorType(Map<String,Object> descriptor);}
    public static String descriptorType(Map<String,Map<String,Object>> state,Map<String,Object> descriptor){
        if(state instanceof DeclarationState indexed)return indexed.descriptorType(descriptor);
        String object=(String)descriptor.get("objectId");String clazz=null;
        for(var image:state.values())if("Membership".equals(image.get("kind"))&&object.equals(image.get("objectId"))){if(clazz!=null)invalid("Multiple memberships");clazz=(String)image.get("classId");}
        if(clazz==null)invalid("Missing historical membership");
        for(var image:state.values())if("ClassDescriptor".equals(image.get("kind"))&&clazz.equals(image.get("classId"))&&Objects.equals(descriptor.get("name"),image.get("name")))return ValueCodec.type(image.get("type"));
        invalid("Missing historical declaration");return null;
    }
    public static Set<String> descriptorSchemaReferences(String membershipId,String classId,String declarationId) {
        var ids=new TreeSet<String>();
        ids.add(key("Membership",membershipId));ids.add(key("Class",classId));
        if(declarationId!=null)ids.add(key("ClassDescriptor",declarationId));
        return ids;
    }
    public static Set<String> schemaReferences(Map<String,Map<String,Object>> state,Map<String,Object> image){
        var ids=new TreeSet<String>();if(image==null)return ids;
        if("Pattern".equals(image.get("kind"))){Object definition=image.get("definition");if(definition instanceof Map<?,?> d&&d.get("objects") instanceof Map<?,?> roles)for(Object raw:roles.values()){var role=ValueCodec.map(raw);String className=(String)role.get("className");for(var clazz:state.values())if("Class".equals(clazz.get("kind"))&&Objects.equals(className,clazz.get("name"))){ids.add(key(clazz));Set<String> fields=PatternCodec.referencedDescriptors(role);for(var declaration:state.values())if("ClassDescriptor".equals(declaration.get("kind"))&&Objects.equals(clazz.get("id"),declaration.get("classId"))&&fields.contains(declaration.get("name")))ids.add(key(declaration));}}}
        if("Class".equals(image.get("kind"))){for(var pattern:state.values())if("Pattern".equals(pattern.get("kind"))){Object definition=pattern.get("definition");if(definition instanceof Map<?,?> d&&d.get("objects") instanceof Map<?,?> roles&&roles.values().stream().anyMatch(raw->Objects.equals(image.get("name"),ValueCodec.map(raw).get("className"))))ids.add(key(pattern));}}
        if("ClassDescriptor".equals(image.get("kind"))){var clazz=find(state,image.get("classId"),"Class");if(clazz!=null)for(var pattern:state.values())if("Pattern".equals(pattern.get("kind"))){var roles=ValueCodec.map(ValueCodec.map(pattern.get("definition")).get("objects"));if(roles.values().stream().anyMatch(raw->{var role=ValueCodec.map(raw);return Objects.equals(clazz.get("name"),role.get("className"))&&PatternCodec.referencedDescriptors(role).contains(image.get("name"));}))ids.add(key(pattern));}}
        if(!"Descriptor".equals(image.get("kind")))return ids;
        for(var m:state.values())if("Membership".equals(m.get("kind"))&&Objects.equals(image.get("objectId"),m.get("objectId"))){ids.addAll(descriptorSchemaReferences((String)m.get("id"),(String)m.get("classId"),null));for(var d:state.values())if("ClassDescriptor".equals(d.get("kind"))&&Objects.equals(m.get("classId"),d.get("classId"))&&Objects.equals(image.get("name"),d.get("name")))ids.add(key(d));}return ids;
    }
    private static void requires(Map<String,Map<String,Object>> state,Object id,String kind){if(find(state,id,kind)==null)invalid("Invalid historical endpoint category");}
    private static void shape(Map<String,Object> m,String kind){
        var allowed=new HashSet<>(List.of("kind","id","namespaceId","createdAt","createdBy","updatedAt","updatedBy"));
        allowed.addAll(switch(kind){case "Namespace"->List.of("name","description");case "Class"->List.of("name","position","order","comment");case "Object"->List.of("position");case "ClassDescriptor"->List.of("classId","name","type","order","comment");case "Descriptor"->List.of("objectId","name","value","geometryId");case "Membership"->List.of("objectId","classId");case "Relationship"->List.of("sourceId","targetId","label","weight");case "ClassRelationship"->List.of("sourceId","targetId","label","comment");case "Pattern"->List.of("name","comment","definition");case "UserTool"->List.of("name","data","comment");case "DGeometry"->List.of("descriptorId","wkt","geometryType","srid","verticalSrid","axisOrder","dimensions");default->List.of();});
        if(!allowed.containsAll(m.keySet()))invalid("Noncanonical historical image fields");
        for(String key:List.of("name","description","comment","label","data"))if(m.containsKey(key)&&!(m.get(key) instanceof String))invalid("Invalid historical text");
        if(m.containsKey("order")&&!(m.get("order") instanceof Long||m.get("order") instanceof Integer))invalid("Invalid historical order");
        if(m.containsKey("position")){if(!(m.get("position") instanceof List<?> list)||list.size()!=2)invalid("Invalid historical position");for(Object x:(List<?>)m.get("position"))if(!(x instanceof Number n)||!Double.isFinite(n.doubleValue()))invalid("Invalid historical position");}
        if(kind.equals("Descriptor")&&!m.containsKey("value"))invalid("Missing historical value");
        if(kind.equals("Pattern")&&!(m.get("definition") instanceof Map<?,?>))invalid("Invalid historical Pattern definition");
        if(m.containsKey("weight"))ValueCodec.normalize("Float",m.get("weight"));
    }
    public static void validate(Map<String,Map<String,Object>> state){validate(state,null,false);}
    static void validate(Map<String,Map<String,Object>> state,PackageWorkspace workspace,boolean typedPackageValues){
        var roots=state.values().stream().filter(m->"Namespace".equals(m.get("kind"))).limit(2).toList();if(roots.size()!=1)invalid("Historical state requires one Namespace");Object namespace=roots.getFirst().get("id");
        Set<String> names=workspace==null?new HashSet<>():workspace.set();Map<String,String> memberships=workspace==null?new HashMap<>():workspace.map();Set<String> geometryOwners=workspace==null?new HashSet<>():workspace.set();Set<String> classEdges=workspace==null?new HashSet<>():workspace.set();
        for(var entry:state.entrySet()) {
            var m=entry.getValue();String kind=(String)m.get("kind");if(!KINDS.contains(kind)||!entry.getKey().equals(key(m)))invalid("Invalid historical image identity");
            shape(m,kind);
            if(!Objects.equals(namespace,m.get("namespaceId")))invalid("Historical image escaped Namespace");
            if(m.containsKey("name")){Object name=m.get("name");if(!(name instanceof String s)||s.isBlank()||s.codePointCount(0,s.length())>255||s.codePoints().anyMatch(Character::isISOControl))invalid("Invalid historical name");
                String owner=Objects.toString(m.get("objectId"),Objects.toString(m.get("classId"),""));if(!names.add(kind+"/"+owner+"/"+name))invalid("Duplicate historical name");}
            switch(kind){
                case "Membership"->{requires(state,m.get("objectId"),"Object");requires(state,m.get("classId"),"Class");if(memberships.put((String)m.get("objectId"),(String)m.get("classId"))!=null)invalid("Multiple historical memberships");}
                case "ClassDescriptor"->{requires(state,m.get("classId"),"Class");ValueCodec.type(m.get("type"));}
                case "Descriptor"->{requires(state,m.get("objectId"),"Object");if(m.containsKey("type"))invalid("Descriptor image duplicates type");String type=descriptorType(state,m);if(!typedPackageValues&&!equal(ValueCodec.normalize(type,m.get("value")),m.get("value")))invalid("Noncanonical historical typed value");
                    if("DGeometry".equals(type)&&m.get("value")!=null){requires(state,m.get("geometryId"),"DGeometry");var g=find(state,m.get("geometryId"),"DGeometry");if(!m.get("id").equals(g.get("descriptorId"))||!equal(m.get("value"),geometryValue(g)))invalid("Historical geometry ownership/value mismatch");}
                    else if(m.get("geometryId")!=null)invalid("Unexpected geometry identity");}
                case "DGeometry"->{requires(state,m.get("descriptorId"),"Descriptor");if(!geometryOwners.add((String)m.get("descriptorId")))invalid("Multiple historical geometries");var d=find(state,m.get("descriptorId"),"Descriptor");if(!m.get("id").equals(d.get("geometryId"))||d.get("value")==null)invalid("Orphan historical geometry");GeometryCodec.normalize(geometryValue(m));if(!Objects.equals(m.get("geometryType"),GeometryCodec.fromWkt(m.get("wkt"),m.get("srid"),m.get("verticalSrid")).get("geometryType")))invalid("Inconsistent historical geometry type");}
                case "Relationship","ClassRelationship"->{if(kind.equals("ClassRelationship"))classEdges.add(m.get("sourceId")+"\u0000"+m.get("targetId")+"\u0000"+m.get("label"));String endpoint=kind.equals("Relationship")?"Object":"Class";requires(state,m.get("sourceId"),endpoint);requires(state,m.get("targetId"),endpoint);if(!(m.get("label") instanceof String s)||s.isBlank())invalid("Invalid relationship label");if(!names.add(kind+"/"+m.get("sourceId")+"/"+m.get("targetId")+"/"+m.get("label")))invalid("Duplicate historical edge");}
                case "UserTool"->{if(!(m.get("data") instanceof String))invalid("Invalid opaque UserTool data");}
                default->{}
            }
        }
        for(var m:state.values())if("Object".equals(m.get("kind"))&&!memberships.containsKey(m.get("id")))invalid("Object without membership");
        for(var m:state.values())if("Relationship".equals(m.get("kind"))) {
            if(!classEdges.contains(memberships.get(m.get("sourceId"))+"\u0000"+memberships.get(m.get("targetId"))+"\u0000"+m.get("label")))invalid("Missing historical Class relationship");
        }
        Map<String,String> classNames=workspace==null?new HashMap<>():workspace.map(),declarations=workspace==null?new HashMap<>():workspace.map();
        for(var image:state.values())if("Class".equals(image.get("kind")))classNames.put((String)image.get("name"),(String)image.get("id"));
        for(var image:state.values())if("ClassDescriptor".equals(image.get("kind")))declarations.put(image.get("classId")+"\u0000"+image.get("name"),(String)image.get("type"));
        Map<String,Map<String,String>> schema=PatternCodec.lookupSchema(classNames,declarations);
        Collection<Map<String,Object>> definitions=new AbstractCollection<>(){
            public int size(){return (int)state.values().stream().filter(image->"Pattern".equals(image.get("kind"))).count();}
            public Iterator<Map<String,Object>> iterator(){return state.values().stream().filter(image->"Pattern".equals(image.get("kind"))).map(image->ValueCodec.map(image.get("definition"))).iterator();}
        };
        try{PatternCodec.validate(definitions,schema,workspace);}catch(ExceptionHandler.DepositException failure){invalid("Invalid historical Pattern definition or compatibility");}
    }

    public static Map<String,Object> geometryValue(Map<String,Object> m){return Json.map("wkt",m.get("wkt"),"srid",m.get("srid"),"verticalSrid",m.get("verticalSrid"),"axisOrder",m.get("axisOrder"),"dimensions",m.get("dimensions"));}
    public static void invalid(String message){throw ExceptionHandler.fail("REVISION_CONFLICT",message);}
}
