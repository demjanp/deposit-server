package deposit.common;

import java.util.*;

/** Portable action journal. A checkpoint is taken after each successful batch command. */
public final class ChangeSet {
    public final Map<String,Map<String,Object>> before;
    public Map<String,Map<String,Object>> after;
    public final Map<String,Set<String>> touches;
    public final Set<String> dependencies;
    public final Map<String,String> kinds;
    private final Storage storage;
    /** A transaction-owned disk index can supply the same journal without copying its states. */
    public interface Storage {
        Map<String,Map<String,Object>> before();
        Map<String,Map<String,Object>> after();
        Map<String,Set<String>> touches();
        Set<String> dependencies();
        Map<String,String> kinds();
        void checkpoint(Map<String,Map<String,Object>> state);
    }
    public ChangeSet(Map<String,Map<String,Object>> state) {storage=null;before=HistoryCodec.copy(state);after=HistoryCodec.copy(state);touches=new TreeMap<>();dependencies=new TreeSet<>();kinds=new TreeMap<>();}
    public ChangeSet(Storage storage) {this.storage=Objects.requireNonNull(storage);before=storage.before();after=storage.after();touches=storage.touches();dependencies=storage.dependencies();kinds=storage.kinds();}
    public void checkpoint(Map<String,Map<String,Object>> state) {
        if(storage!=null){storage.checkpoint(state);after=storage.after();return;}
        for(String id:HistoryCodec.union(after.keySet(),state.keySet())) {
            var a=after.get(id);var b=state.get(id);var fields=HistoryCodec.fields(a,b);
            if(fields.isEmpty())continue;
            kinds.put(id,(String)(b==null?a:b).get("kind"));
            touches.computeIfAbsent(id,k->new TreeSet<>()).addAll(fields);
            dependencies.addAll(HistoryCodec.references(a));dependencies.addAll(HistoryCodec.references(b));
            dependencies.addAll(HistoryCodec.schemaReferences(after,a));dependencies.addAll(HistoryCodec.schemaReferences(state,b));
            if(fields.contains("*")) {
                dependencies.add(id);
                for(var image:after.values())if(HistoryCodec.references(image).contains(id))dependencies.add(HistoryCodec.key(image));
                for(var image:state.values())if(HistoryCodec.references(image).contains(id))dependencies.add(HistoryCodec.key(image));
            }
        }
        after=HistoryCodec.copy(state);
    }
    public Map<String,Object> portable() {return Json.map("touches",touches,"dependencies",dependencies);}
}
