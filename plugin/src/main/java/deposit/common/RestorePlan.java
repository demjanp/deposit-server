package deposit.common;
import java.util.*;
public record RestorePlan(UndoPlan plan) {
    public Map<String,Object> data(){var d=plan.data();long created=0,deleted=0,updated=0;for(String id:plan.changes().touches.keySet()){if(!plan.changes().before.containsKey(id))created++;else if(!plan.changes().after.containsKey(id))deleted++;else updated++;}d.put("counts",Json.map("created",created,"deleted",deleted,"updated",updated));return d;}
}
