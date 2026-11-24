package deposit.common;

import java.util.*;
import java.util.function.*;

/** Portable action journal. A checkpoint is taken after each successful batch command. */
public final class ChangeSet {
    public final Map<String,Map<String,Object>> before;
    public Map<String,Map<String,Object>> after;
    public final Map<String,Set<String>> touches;
    public final Set<String> dependencies;
    public final Map<String,String> kinds;
    private final Storage storage;
    private Set<String> seen;
    private Map<String,String> beforeResources, afterResources;

    public record Images(Map<String,Map<String,Object>> state,Map<String,String> resources,Map<String,Set<String>> descriptorSchema) {
        public Images() {this(new TreeMap<>(),new HashMap<>(),new HashMap<>());}
    }

    /** Sparse action journal whose checkpoints carry only affected images. */
    public ChangeSet() {
        this(Map.of());
        seen=new HashSet<>();beforeResources=new HashMap<>();afterResources=new HashMap<>();
    }
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
            record(id,a,b,fields);
            dependencies.addAll(HistoryCodec.schemaReferences(after,a));dependencies.addAll(HistoryCodec.schemaReferences(state,b));
            if(fields.contains("*")) {
                dependencies.add(id);
                for(var image:after.values())if(HistoryCodec.references(image).contains(id))dependencies.add(HistoryCodec.key(image));
                for(var image:state.values())if(HistoryCodec.references(image).contains(id))dependencies.add(HistoryCodec.key(image));
            }
        }
        after=HistoryCodec.copy(state);
    }
    /** Merge command-local images and their reference neighborhoods. */
    public void checkpointAffected(Images prior,Images current,
            BiFunction<Map<String,Object>,Boolean,Set<String>> schema,
            Function<Map<String,Object>,Set<String>> reverse) {
        var priorReverse=new HashMap<String,Set<String>>();
        for(var image:prior.state().values())
            for(String id:HistoryCodec.references(image))
                priorReverse.computeIfAbsent(id,key->new HashSet<>()).add(HistoryCodec.key(image));
        for(String id:HistoryCodec.union(prior.state().keySet(),current.state().keySet())) {
            var a=prior.state().get(id);
            var b=current.state().get(id);
            if(seen.add(id)&&a!=null) {
                before.put(id,a);
                if(prior.resources().containsKey(id))beforeResources.put(id,prior.resources().get(id));
            }
            if(b==null)after.remove(id);else after.put(id,b);
            String resource=current.resources().get(id);
            if(resource==null)afterResources.remove(id);else afterResources.put(id,resource);
            var fields=HistoryCodec.fields(a,b);
            if(fields.isEmpty())continue;
            record(id,a,b,fields);
            if(a!=null)dependencies.addAll(schema.apply(a,true));
            if(b!=null)dependencies.addAll(schema.apply(b,false));
            if(fields.contains("*")) {
                dependencies.add(id);
                dependencies.addAll(priorReverse.getOrDefault(id,Set.of()));
                if(b!=null)dependencies.addAll(reverse.apply(b));
            }
        }
    }

    private void record(String id,Map<String,Object> old,Map<String,Object> current,Set<String> fields) {
        kinds.put(id,(String)(current==null?old:current).get("kind"));
        touches.computeIfAbsent(id,key->new TreeSet<>()).addAll(fields);
        dependencies.addAll(HistoryCodec.references(old));dependencies.addAll(HistoryCodec.references(current));
    }

    public String retainedResource(Map<String,Object> image,boolean prior) {
        if(image==null||!"Descriptor".equals(image.get("kind"))||image.get("value")==null)return null;
        if(seen!=null)return (prior?beforeResources:afterResources).get(HistoryCodec.key(image));
        if(!"DResource".equals(HistoryCodec.descriptorType(prior?before:after,image)))return null;
        return (String)ValueCodec.map(image.get("value")).get("resourceId");
    }

    public Map<String,Object> portable() {return Json.map("touches",touches,"dependencies",dependencies);}
}
