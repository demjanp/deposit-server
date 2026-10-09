package deposit.common;

import java.util.*;

/** Portable action journal. A checkpoint is taken after each successful batch command. */
public final class ChangeSet {
    public final Map<String,Map<String,Object>> before;
    public Map<String,Map<String,Object>> after;
    public final Map<String,Set<String>> touches=new TreeMap<>();
    public final Set<String> dependencies=new TreeSet<>();
    public final Map<String,String> kinds=new TreeMap<>();
    public ChangeSet(Map<String,Map<String,Object>> state) {before=HistoryCodec.copy(state);after=HistoryCodec.copy(state);}
    public void checkpoint(Map<String,Map<String,Object>> state) {
        for(String id:HistoryCodec.union(after.keySet(),state.keySet())) {
            var a=after.get(id);var b=state.get(id);var fields=HistoryCodec.fields(a,b);
            if(fields.isEmpty())continue;
            kinds.put(id,(String)(b==null?a:b).get("kind"));
            touches.computeIfAbsent(id,k->new TreeSet<>()).addAll(fields);
            dependencies.addAll(HistoryCodec.references(a));dependencies.addAll(HistoryCodec.references(b));
            dependencies.addAll(HistoryCodec.schemaReferences(after,a));dependencies.addAll(HistoryCodec.schemaReferences(state,b));
            if(fields.contains("*")) {
                dependencies.add(id);
                for(var image:after.values())if(HistoryCodec.references(image).contains(id))dependencies.add((String)image.get("id"));
                for(var image:state.values())if(HistoryCodec.references(image).contains(id))dependencies.add((String)image.get("id"));
            }
        }
        after=HistoryCodec.copy(state);
    }
    public Map<String,Object> portable() {return Json.map("touches",touches,"dependencies",dependencies);}
}
