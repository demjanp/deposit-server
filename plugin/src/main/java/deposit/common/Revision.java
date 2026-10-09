package deposit.common;
import java.util.*;
public record Revision(String id,long sequence,Map<String,Object> data,Map<String,Map<String,Object>> before,Map<String,Map<String,Object>> after,Map<String,Set<String>> touches,Set<String> dependencies) {
    public String kind(){return (String)data.get("kind");}
    public String reverses(){return (String)data.getOrDefault("reversesRevisionId",data.get("reappliesRevisionId"));}
}
