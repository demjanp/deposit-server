package deposit.service;

import deposit.common.Json;
import java.util.*;

/** Bounded tabular error diagnostics; validation and raw-value policy stay with callers. */
final class TabularDiagnostics {
    private static final int MAX_RETAINED=1000;
    private final List<Map<String,Object>> items=new ArrayList<>();
    private long count;

    void add(String code,String rowId,String column,String message){
        count++;
        if(items.size()<MAX_RETAINED)items.add(Json.map("severity","error","code",code,"rowId",rowId,"column",column,"message",message));
    }
    List<Map<String,Object>> items(){return items;}
    boolean truncated(){return count>items.size();}
}
