package deposit.common;
import java.util.*;
public record UndoPlan(long sequence,String targetId,Map<String,Map<String,Object>> result,List<Map<String,Object>> conflicts,ChangeSet changes) {
    public Map<String,Object> data(){return Json.map("sequence",sequence,"expectedRevision",sequence,"targetRevisionId",targetId,"affectedIds",new ArrayList<>(changes.touches.keySet()),"count",changes.touches.size(),"conflicts",conflicts);}
}
