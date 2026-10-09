package deposit.common;
import deposit.repository.*;
import deposit.service.HistoryService;
import org.neo4j.graphdb.Transaction;

/** Transaction-bound facade: mutations own one service action, recording requires an already locked action. */
public final class Versioning {
    private Versioning(){}
    private static HistoryRepository repository(Transaction tx,RequestContext c){if(tx!=c.tx)throw new IllegalArgumentException("History requires action transaction");return new HistoryRepository(tx,new CoreRepository(tx,c.timestamp));}
    public static Revision record(Transaction tx,RequestContext c,ChangeSet changes){return repository(tx,c).append(c.namespaceId,c.actorId,c.operationId,c.revision,c.historyKind,"Action",changes,c.historyLink,c.historyTarget,c.timestamp);}
    public static UndoPlan previewUndo(Transaction tx,RequestContext c,String id){repository(tx,c);return new HistoryService(c).previewUndo(id);}
    public static RestorePlan previewRestore(Transaction tx,RequestContext c,String id){repository(tx,c);return new HistoryService(c).previewRestore(id);}
    public static Revision undo(Transaction tx,RequestContext c,String id){return applied(tx,c,"undo",id);}
    public static Revision redo(Transaction tx,RequestContext c,String id){return applied(tx,c,"redo",id);}
    public static Revision restore(Transaction tx,RequestContext c,String id){return applied(tx,c,"restore",id);}
    private static Revision applied(Transaction tx,RequestContext c,String kind,String id){var r=repository(tx,c);String key=kind.equals("redo")?"undoRevisionId":kind.equals("restore")?"targetRevisionId":"revisionId";var result=new HistoryService(c).reverse(kind,id==null?java.util.Map.of():Json.map(key,id));Object revisionId=result.data().get("revisionId");return revisionId==null?null:r.revision(c.namespaceId,(String)revisionId);}
    public static void setTag(Transaction tx,RequestContext c,String id,String tag){repository(tx,c);new HistoryService(c).tag(Json.map("revisionId",id,"tag",tag));}
}
