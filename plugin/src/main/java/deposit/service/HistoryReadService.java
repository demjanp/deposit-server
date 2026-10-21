package deposit.service;

import deposit.common.*;
import deposit.common.Results.*;
import deposit.repository.*;
import java.util.*;
import java.util.stream.*;
import org.neo4j.graphdb.Node;

/** Owns history read projections, page cursors and revision-stable streams. */
final class HistoryReadService extends BaseService {
    private final HistoryRepository history;
    HistoryReadService(RequestContext c){super(c);history=new HistoryRepository(c.tx,repo);}

    private Node root(){return repo.namespace(c.namespaceId);}
    private void read(String role){readContext();scoped(role);}
    long generation(){return ((Number)root().getProperty("historyAvailability",0L)).longValue();}
    void stable(long sequence,long generation){c.state.kernel.assertOpen();if(System.currentTimeMillis()-c.state.kernel.startTime()>30000)c.fail("CANCELLED","Read timeout exceeded");authenticate();requireRole(c.namespaceId,"viewer");if(repo.sequence(c.namespaceId)!=sequence||generation()!=generation)c.fail("REVISION_CONFLICT","History changed during read");}

    DataRow revision(Map<String,Object> request){fields(request);read("viewer");return new DataRow(Json.map("sequence",c.revision,"revisionId",root().getProperty("headRevisionId",null),"historyMode",root().getProperty("historyMode"),"historyEpochId",root().getProperty("historyEpochId",null)),c.meta());}
    ValueRow restored(Map<String,Object> request){fields(request);read("viewer");Object head=root().getProperty("headRevisionId",null);return new ValueRow(head!=null&&history.revision(c.namespaceId,(String)head).kind().equals("restore"),c.meta());}
    Stream<DataRow> records(Map<String,Object> request){fields(request);read("viewer");long sequence=c.revision,generation=generation();var values=history.revisions(c.namespaceId);stable(sequence,generation);return values.stream().map(r->{c.server.storagePoint("history-read-row");stable(sequence,generation);return new DataRow(r.data(),c.meta());}).onClose(()->stable(sequence,generation));}
    DataRow page(Map<String,Object> request){
        fields(request,"pageSize","cursor");read("viewer");long sequence=c.revision,generation=generation();long limit=integer(request.getOrDefault("pageSize",200L));if(limit<1||limit>1000)c.fail("LIMIT_EXCEEDED","Page size must be 1..1000");int offset=0;
        if(request.containsKey("cursor")){var cursor=CursorCodec.decode(c,string(request.get("cursor")));if(!c.namespaceId.equals(cursor.get("namespaceId"))||!c.actorId.equals(cursor.get("actorId"))||!"history".equals(cursor.get("kind")))c.fail("SCOPE_MISMATCH","Cursor scope differs");if(sequence!=((Number)cursor.get("sequence")).longValue()||generation!=((Number)cursor.get("generation")).longValue())c.fail("REVISION_CONFLICT","History cursor changed");offset=((Number)cursor.get("offset")).intValue();}
        var all=history.revisions(c.namespaceId);if(offset<0||offset>all.size())c.fail("VALIDATION_FAILED","Invalid history offset");int end=(int)Math.min(all.size(),offset+limit);String next=end==all.size()?null:CursorCodec.encode(c,Json.map("kind","history","namespaceId",c.namespaceId,"actorId",c.actorId,"sequence",sequence,"generation",generation,"offset",end,"expires",System.currentTimeMillis()+900000));stable(sequence,generation);var meta=c.meta();meta.put("nextCursor",next);return new DataRow(Json.map("items",all.subList(offset,end).stream().map(Revision::data).toList()),meta);
    }
}
