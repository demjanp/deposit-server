package deposit.service;

import deposit.common.*;
import java.util.*;

/** Iterative branch-and-bound matching followed by bounded optimal-solution enumeration. */
@FunctionalInterface interface EdgeMatch {boolean matches(String source,String target,String label);}
/** Iterative branch-and-bound, followed by complete enumeration at the proven minimum. */
final class MappingSolver {
    final ImportScratch indexes;final DiskJsonList solutions;final EdgeMatch edges;
    int count,best,forced,depth,created;
    MappingSolver(ImportScratch indexes,DiskJsonList solutions,EdgeMatch edges){this.indexes=indexes;this.solutions=solutions;this.edges=edges;}
    void solve(){
        indexes.update("UPDATE sources SET candidateCount=(SELECT count(*) FROM candidates WHERE source=sources.id)");
        indexes.update("CREATE TABLE ordering AS SELECT row_number() OVER (ORDER BY candidateCount,id)-1 AS position,id,sum(CASE WHEN candidateCount=0 THEN 1 ELSE 0 END) OVER (ORDER BY candidateCount,id ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING) AS forced FROM sources");
        indexes.update("CREATE UNIQUE INDEX ordering_position ON ordering(position)");
        count=((Number)indexes.one("SELECT count(*) AS n FROM sources").get("n")).intValue();best=count;
        var first=indexes.one("SELECT forced FROM ordering WHERE position=0");forced=first==null?0:((Number)first.get("forced")).intValue();
        if(best>forced)pass(false);
        indexes.update("DELETE FROM assignments");indexes.update("DELETE FROM frames");pass(true);
    }
    private void pass(boolean enumerate){depth=0;created=0;
        while(depth>=0){indexes.check();
            if(depth==count){if(!completeKeylessSupport()){retreat();continue;}if(enumerate&&created==best){try{solutions.add(indexes.assignments());if(solutions.size()>=2)return;}catch(RuntimeException failure){throw indexes.interrupted==null?failure:indexes.interrupted;}}else if(!enumerate&&created<best){best=created;if(best==forced)return;}retreat();continue;}
            var source=indexes.one("SELECT id,forced FROM ordering WHERE position=?",depth);String id=(String)source.get("id");int lower=created+((Number)source.get("forced")).intValue();
            if(lower>best||!enumerate&&lower>=best){retreat();continue;}
            var frame=indexes.one("SELECT lastTarget,creation FROM frames WHERE position=?",depth);
            if(frame==null){indexes.update("INSERT INTO frames(position) VALUES(?)",depth);frame=Json.map("lastTarget",null,"creation",0);}
            if(((Number)frame.get("creation")).intValue()!=0){retreat();continue;}
            Object after=frame.get("lastTarget");var next=after==null?indexes.one("SELECT target FROM candidates WHERE source=? ORDER BY target LIMIT 1",id):indexes.one("SELECT target FROM candidates WHERE source=? AND target>? ORDER BY target LIMIT 1",id,after);
            if(next!=null){String target=(String)next.get("target");indexes.update("UPDATE frames SET lastTarget=? WHERE position=?",target,depth);indexes.update("INSERT INTO assignments(source,target) VALUES(?,?) ON CONFLICT(source) DO UPDATE SET target=excluded.target",id,target);if(compatible(id)){depth++;indexes.update("DELETE FROM frames WHERE position=?",depth);}continue;}
            indexes.update("UPDATE frames SET creation=1 WHERE position=?",depth);indexes.update("INSERT INTO assignments(source,target) VALUES(?,NULL) ON CONFLICT(source) DO UPDATE SET target=NULL",id);created++;depth++;indexes.update("DELETE FROM frames WHERE position=?",depth);
        }
    }
    private boolean completeKeylessSupport(){
        return indexes.one("SELECT 1 AS invalid FROM assignments a JOIN sources s ON s.id=a.source WHERE s.keyless=1 AND a.target IS NOT NULL AND NOT EXISTS (SELECT 1 FROM edges e JOIN assignments neighbor ON neighbor.source=CASE WHEN e.source=a.source THEN e.target ELSE e.source END WHERE (e.source=a.source OR e.target=a.source) AND neighbor.target IS NOT NULL) LIMIT 1")==null;
    }
    private void retreat(){
        if(depth<count){var current=indexes.one("SELECT id FROM ordering WHERE position=?",depth);indexes.update("DELETE FROM assignments WHERE source=?",current.get("id"));indexes.update("DELETE FROM frames WHERE position=?",depth);}
        depth--;if(depth<0)return;var parent=indexes.one("SELECT id FROM ordering WHERE position=?",depth);var assignment=indexes.one("SELECT target FROM assignments WHERE source=?",parent.get("id"));if(assignment!=null&&assignment.get("target")==null)created--;indexes.update("DELETE FROM assignments WHERE source=?",parent.get("id"));
    }
    private boolean compatible(String id){
        try(var links=indexes.query("SELECT a.target AS source,b.target AS target,e.label FROM edges e JOIN assignments a ON a.source=e.source JOIN assignments b ON b.source=e.target WHERE (e.source=? OR e.target=?) AND a.target IS NOT NULL AND b.target IS NOT NULL",id,id)){
            for(var link:links){indexes.check();if(!edges.matches((String)link.get("source"),(String)link.get("target"),(String)link.get("label")))return false;}return true;
        }
    }
}
