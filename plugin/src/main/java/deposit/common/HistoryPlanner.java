package deposit.common;

import java.util.*;

/** Pure planner shared by inspection and application. No live schema, graph writes or clocks. */
public final class HistoryPlanner {
    private HistoryPlanner() {}
    /** Each reversal chain has one tip. Earlier IDs can never reverse the same effect twice. */
    public static Map<String,String> tips(List<Revision> revisions){var roots=new HashMap<String,String>();var tips=new HashMap<String,String>();for(var r:revisions){String root=r.reverses()==null?r.id():roots.get(r.reverses());if(root==null)HistoryCodec.invalid("Broken reversal chain");roots.put(r.id(),root);tips.put(root,r.id());}var result=new HashMap<String,String>();roots.forEach((id,root)->result.put(id,tips.get(root)));return result;}
    public static boolean eligible(Revision target,List<Revision> revisions,boolean redo){
        if(redo?!target.kind().equals("undo"):!Set.of("edit","import","redo","restore").contains(target.kind()))return false;
        String tip=tips(revisions).get(target.id());if(target.id().equals(tip))return true;
        // An undo may be reapplied again after the redo was itself undone.
        if(redo){Revision last=revisions.stream().filter(r->r.id().equals(tip)).findFirst().orElseThrow();return last.kind().equals("undo");}return false;
    }
    public static Map<String,Map<String,Object>> reconstruct(List<Revision> epoch,String id){var state=new TreeMap<String,Map<String,Object>>();boolean baseline=false;for(var r:epoch){if(r.kind().equals("baseline"))baseline=true;if(!baseline)HistoryCodec.invalid("Missing epoch baseline");for(String key:HistoryCodec.union(r.before().keySet(),r.after().keySet())){if(!r.kind().equals("baseline")&&!HistoryCodec.equal(HistoryCodec.scientific(state.get(key)),HistoryCodec.scientific(r.before().get(key))))HistoryCodec.invalid("Historical before image differs from preceding state");if(r.after().containsKey(key))state.put(key,r.after().get(key));else state.remove(key);}HistoryCodec.validate(state);if(r.id().equals(id))return HistoryCodec.copy(state);}throw ExceptionHandler.fail("NOT_FOUND","Revision not found");}
    public static UndoPlan inverse(long sequence,Map<String,Map<String,Object>> current,Revision target,List<Revision> epoch){
        var result=HistoryCodec.copy(current);var conflicts=new ArrayList<Map<String,Object>>();var tips=tips(epoch);
        for(var later:epoch)if(later.sequence()>target.sequence()&&later.reverses()==null&&!later.kind().equals("baseline")) {
            Revision tip=epoch.stream().filter(r->r.id().equals(tips.get(later.id()))).findFirst().orElseThrow();if(tip.kind().equals("undo"))continue;
            for(String dependency:target.dependencies())if(later.touches().getOrDefault(dependency,Set.of()).contains("*"))conflicts.add(Json.map("entityId",dependency,"revisionId",later.id(),"reason","historical-schema-dependency"));
            for(var touch:target.touches().entrySet()) {
                var other=later.touches().get(touch.getKey());
                if(other!=null&&(touch.getValue().contains("*")||other.contains("*")||!Collections.disjoint(touch.getValue(),other)))conflicts.add(Json.map("entityId",touch.getKey(),"fields",new ArrayList<>(other),"revisionId",later.id(),"reason","intervening-touch"));
                if(touch.getValue().contains("*")&&(later.dependencies().contains(touch.getKey())||target.dependencies().stream().anyMatch(later.touches()::containsKey)))conflicts.add(Json.map("entityId",touch.getKey(),"revisionId",later.id(),"reason","structural-dependency"));
            }
        }
        for(String id:HistoryCodec.union(target.before().keySet(),target.after().keySet())) {
            var before=target.before().get(id);var after=target.after().get(id);var now=current.get(id);Set<String> fields=target.touches().getOrDefault(id,HistoryCodec.fields(before,after));
            if(fields.contains("*")) {
                if(!HistoryCodec.equal(HistoryCodec.scientific(now),HistoryCodec.scientific(after)))conflicts.add(Json.map("entityId",id,"fields",List.of("*"),"reason","state-mismatch"));
                if(before==null)result.remove(id);else result.put(id,new LinkedHashMap<>(before));
            }else {
                if(now==null){conflicts.add(Json.map("entityId",id,"reason","missing-entity"));continue;}
                var image=new LinkedHashMap<>(now);for(String field:fields){if(!HistoryCodec.equal(now.get(field),after.get(field)))conflicts.add(Json.map("entityId",id,"fields",List.of(field),"reason","state-mismatch"));if(before.containsKey(field))image.put(field,before.get(field));else image.remove(field);}result.put(id,image);
            }
        }
        validate(result,conflicts);var change=new ChangeSet(current);change.checkpoint(result);
        return new UndoPlan(sequence,target.id(),result,conflicts,change);
    }
    public static RestorePlan restore(long sequence,Map<String,Map<String,Object>> current,Map<String,Map<String,Object>> target,String id){var conflicts=new ArrayList<Map<String,Object>>();validate(target,conflicts);var changes=new ChangeSet(current);changes.checkpoint(target);return new RestorePlan(new UndoPlan(sequence,id,HistoryCodec.copy(target),conflicts,changes));}
    private static void validate(Map<String,Map<String,Object>> state,List<Map<String,Object>> conflicts){try{HistoryCodec.validate(state);}catch(ExceptionHandler.DepositException e){conflicts.add(Json.map("reason","invalid-resulting-model"));}}
}
