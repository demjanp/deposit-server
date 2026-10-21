package deposit.service;

import deposit.common.*;
import deposit.repository.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.neo4j.graphdb.Node;

/** Shared import comparisons and schema projections whose semantics must remain identical. */
final class ImportSemantics {
    private ImportSemantics() {}

    static boolean forcedClass(String className,Map<String,Object> rules){for(Object raw:(List<?>)rules.getOrDefault("alwaysCreate",List.of()))if(className.equals(BaseService.name(raw)))return true;return false;}

    static Set<String> connectedRoles(String root,Map<String,Object> roles){var visited=new HashSet<String>();var queue=new ArrayDeque<String>();visited.add(root);queue.add(root);while(!queue.isEmpty()){String role=queue.remove();for(var entry:roles.entrySet())for(Object raw:(List<?>)ValueCodec.map(entry.getValue()).get("relationships")){String target=(String)ValueCodec.map(raw).get("targetName"),next=entry.getKey().equals(role)?target:target.equals(role)?entry.getKey():null;if(next!=null&&visited.add(next))queue.add(next);}}return visited;}

    static TreeMap<String,Map<String,String>> namespaceSchema(CoreRepository repo,String namespace){return repo.namespaceSchema(namespace);}

    static Map<String,Object> mapPatternClasses(Map<String,Object> definition,Map<String,String> mapping){var normalized=PatternCodec.normalize(definition);var objects=new LinkedHashMap<String,Object>();ValueCodec.map(normalized.get("objects")).forEach((role,raw)->{var spec=new LinkedHashMap<>(ValueCodec.map(raw));spec.put("className",mapping.getOrDefault(spec.get("className"),(String)spec.get("className")));objects.put(role,spec);});return Json.map("objects",objects);}

    static boolean samePatternDefinition(String stored,Map<String,Object> incoming){return Objects.equals(PatternCodec.normalize(Json.read(stored)),PatternCodec.normalize(incoming));}
    static boolean samePatternDefinition(String stored,Map<String,Object> incoming,Map<String,? extends Map<String,String>> schema){return Objects.equals(canonicalPattern(Json.read(stored),schema),canonicalPattern(incoming,schema));}
    private static Map<String,Object> canonicalPattern(Map<String,Object> definition,Map<String,? extends Map<String,String>> schema){var normalized=PatternCodec.normalize(definition);for(Object raw:ValueCodec.map(normalized.get("objects")).values()){var role=ValueCodec.map(raw);Map<String,String> fields=schema.get(role.get("className"));for(Object rd:(List<?>)role.get("descriptors")){var descriptor=ValueCodec.map(rd);if(fields!=null&&Objects.equals(descriptor.get("type"),fields.get(descriptor.get("name"))))descriptor.remove("type");}}return normalized;}

    static boolean resourceEquivalent(ResourceRepository resources,Object incoming,Object target,Map<String,Object> graph,String targetNamespace){if(incoming==null||target==null)return Objects.equals(incoming,target);String sourceId=(String)ValueCodec.map(incoming).get("resourceId"),targetId=(String)ValueCodec.map(target).get("resourceId");Map<String,Object> source=null;for(Object raw:(List<?>)graph.get("resources")){var row=ValueCodec.map(raw);if(sourceId.equals(row.get("id"))){source=row;break;}}Node dest=resources.resource(targetNamespace,targetId);return source!=null&&dest!=null&&Objects.equals(source.get("sha256"),dest.getProperty("checksum",null))&&Objects.equals(source.get("bytes"),dest.getProperty("bytes",null))&&Objects.equals(source.get("mediaType"),dest.getProperty("mediaType",null))&&Objects.equals(source.get("filename"),dest.getProperty("filename",null));}

    static String encode(Object value){var bytes=new ByteArrayOutputStream();Json.write(bytes,value);return bytes.toString(StandardCharsets.UTF_8);}
}
