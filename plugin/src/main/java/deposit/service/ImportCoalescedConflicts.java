package deposit.service;

import deposit.common.*;
import java.util.*;

/** Detects conflicting writes when multiple source Objects coalesce onto one receiving Object. */
final class ImportCoalescedConflicts {
    private ImportCoalescedConflicts() {}

static void appendCoalescedDescriptorConflicts(Map<String,Object> graph,Map<String,Object> mappings,Map<String,Object> rules,ImportScratch indexes,Collection<Object> conflicts){
Map<String,Object> values=indexes.map(),sources=indexes.map();var publicIds=ValueCodec.map(rules.getOrDefault("_tabularIds",Map.of()));
for(Object raw:(List<?>)graph.get("descriptors")){indexes.check();var descriptor=ValueCodec.map(raw);String source=(String)descriptor.get("objectId");Object target=mappings.get(source);if(!(target instanceof String))continue;String name=(String)descriptor.get("name"),key=target+"\u0000"+name;Object incoming=descriptor.get("value");if(values.containsKey(key)&&!Objects.equals(values.get(key),incoming))conflicts.add(Json.map("kind","coalescedValue","sourceIds",List.of(publicIds.getOrDefault(sources.get(key),sources.get(key)),publicIds.getOrDefault(source,source)),"descriptor",name));else if(!values.containsKey(key)){values.put(key,incoming);sources.put(key,source);}}
}
}
