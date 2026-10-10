package deposit.service;

import deposit.common.*;
import deposit.common.Results.*;
import deposit.repository.*;
import java.util.*;
import java.util.stream.*;
import org.neo4j.graphdb.Node;

/** Authorization, action orchestration and shared preview/apply planning. */
public final class HistoryService extends ActionService {
    private final HistoryRepository history;
    public HistoryService(RequestContext c){super(c);history=new HistoryRepository(c.tx,repo);}
    /** Immutable provenance bundle for exchange; snapshots remain source history, never local revisions. */
    public Map<String,Object> historyExport(){
        enabled();requireRole(c.namespaceId,"owner");String epochId=(String)root().getProperty("historyEpochId");Node epoch=history.epoch(c.namespaceId,epochId);var revisions=history.revisions(c.namespaceId).stream().filter(r->epochId.equals(r.data().get("historyEpochId"))).toList();
        if(epoch==null||revisions.isEmpty()||!Objects.equals(epoch.getProperty("baselineRevisionId"),revisions.getFirst().id()))c.fail("HISTORY_UNAVAILABLE","History epoch has no reconstructable baseline");
        String head=(String)root().getProperty("headRevisionId",null);if(head==null)c.fail("HISTORY_UNAVAILABLE","History head is unavailable");HistoryPlanner.reconstruct(revisions,head);
        var portable=new ArrayList<Map<String,Object>>();var state=new TreeMap<String,Map<String,Object>>();for(Revision revision:revisions){var data=new LinkedHashMap<>(revision.data());data.remove("bootId");var after=new TreeMap<>(state);revision.after().forEach((key,image)->after.put(key,image));revision.before().keySet().stream().filter(key->!revision.after().containsKey(key)).forEach(after::remove);
            var rows=new ArrayList<Map<String,Object>>();for(Object raw:(List<?>)data.get("changes")){var row=new LinkedHashMap<>(ValueCodec.map(raw));String kind=(String)row.get("entityKind"),id=(String)row.get("entityId"),key=HistoryCodec.key(kind,id);Map<String,Object> before=state.get(key),next=after.get(key);row.put("before",portableImage(state,before));row.put("after",portableImage(after,next));rows.add(row);}data.put("changes",rows);portable.add(data);state=after;}
        return Json.map("namespaceId",c.namespaceId,"historyEpochId",epochId,"baseline",portable.getFirst(),"revisions",portable,"complete",true);
    }
    /** Export an epoch through fixed record spools, retaining only the current image index. */
    public Map<String,Object> historyExport(ExportRecords records){
        enabled();String epochId=(String)root().getProperty("historyEpochId");Node epoch=history.epoch(c.namespaceId,epochId);
        if(epoch==null)c.fail("HISTORY_UNAVAILABLE","History epoch is unavailable");
        var state=new ExportState(records);Set<String> seen=Collections.newSetFromMap(records.<Boolean>index());String previous=null,first=null;long sequence=-1;
        try(var revisions=c.tx.execute("MATCH (r:Revision {namespaceId:$namespaceId,historyEpochId:$epochId}) RETURN r ORDER BY r.sequence,r.id",Map.of("namespaceId",c.namespaceId,"epochId",epochId))){
            while(revisions.hasNext()){
                c.state.kernel.assertOpen();Node revision=(Node)revisions.next().get("r");var metadata=history.portableMetadata(revision,records);String id=CoreRepository.id(revision);long nextSequence=((Number)metadata.get("sequence")).longValue();
                if(!Objects.equals(previous,metadata.get("parentRevisionId"))||nextSequence<sequence)c.fail("HISTORY_UNAVAILABLE","History chain is incomplete");
                if(first==null){first=id;if(!"baseline".equals(metadata.get("kind"))||!id.equals(epoch.getProperty("baselineRevisionId")))c.fail("HISTORY_UNAVAILABLE","History baseline is unavailable");}
                sequence=nextSequence;int start=records.list("history-raw-changes").size();seen.clear();
                try(var links=revision.getRelationships(org.neo4j.graphdb.Direction.OUTGOING,org.neo4j.graphdb.RelationshipType.withName("HAS_CHANGE"))){for(var link:links){Node snapshot=link.getEndNode();String kind=(String)snapshot.getProperty("entityKind"),entityId=(String)snapshot.getProperty("entityId"),key=HistoryCodec.key(kind,entityId);if(!seen.add(key))c.fail("HISTORY_UNAVAILABLE","Duplicate history change");
                    Map<String,Object> before=Boolean.TRUE.equals(snapshot.getProperty("beforeExists"))?Json.sourceRecord((String)snapshot.getProperty("beforeJson")):null,after=Boolean.TRUE.equals(snapshot.getProperty("afterExists"))?Json.sourceRecord((String)snapshot.getProperty("afterJson")):null;
                    if(before!=null&&!key.equals(HistoryCodec.key(before))||after!=null&&!key.equals(HistoryCodec.key(after))||!Objects.equals(before,state.get(key)))c.fail("HISTORY_UNAVAILABLE","Historical before image is inconsistent");
                    records.add("history-raw-changes",Json.map("entityKind",kind,"entityId",entityId,"before",portableImage(state,before),"after",after));
                }}
                int end=records.list("history-raw-changes").size();
                for(var change:records.slice("history-raw-changes",start,end)){String key=HistoryCodec.key((String)change.get("entityKind"),(String)change.get("entityId"));if(change.get("after")==null)state.remove(key);else state.put(key,ValueCodec.map(change.get("after")));}
                state.rebuild();records.validateHistoryState(state);
                int portableStart=records.list("history-changes").size();
                for(var change:records.slice("history-raw-changes",start,end)){var wire=new LinkedHashMap<>(change);wire.put("after",portableImage(state,change.get("after")==null?null:ValueCodec.map(change.get("after"))));records.add("history-changes",wire);}
                metadata.put("changes",records.slice("history-changes",portableStart,records.list("history-changes").size()));records.addRevision("history-revisions",metadata);previous=id;
            }
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        if(first==null||!Objects.equals(previous,root().getProperty("headRevisionId",null)))c.fail("HISTORY_UNAVAILABLE","History head is unavailable");
        return Json.map("namespaceId",c.namespaceId,"historyEpochId",epochId,"baseline",records.revisions("history-revisions").getFirst(),"revisions",records.revisions("history-revisions"),"complete",true);
    }
    private static final class ExportState extends AbstractMap<String,Map<String,Object>> implements HistoryCodec.DeclarationState {
        final ExportRecords records;final Map<String,Long> rows;final Map<String,String> memberships,declarations;
        ExportState(ExportRecords records){this.records=records;rows=records.index();memberships=records.index();declarations=records.index();}
        @Override public Map<String,Object> get(Object key){Long row=rows.get(key);return row==null?null:records.list("history-state").get(Math.toIntExact(row));}
        @Override public Map<String,Object> put(String key,Map<String,Object> image){var prior=get(key);try{long row=records.list("history-state").size();records.add("history-state",image);rows.put(key,row);return prior;}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
        @Override public Map<String,Object> remove(Object key){var prior=get(key);rows.remove(key);return prior;}
        void rebuild(){memberships.clear();declarations.clear();for(var image:values())if("Membership".equals(image.get("kind"))){if(memberships.put((String)image.get("objectId"),(String)image.get("classId"))!=null)HistoryCodec.invalid("Duplicate membership");}else if("ClassDescriptor".equals(image.get("kind"))){if(declarations.put(image.get("classId")+"\u0000"+image.get("name"),(String)image.get("type"))!=null)HistoryCodec.invalid("Duplicate declaration");}}
        public String descriptorType(Map<String,Object> descriptor){String clazz=memberships.get(descriptor.get("objectId"));String type=clazz==null?null:declarations.get(clazz+"\u0000"+descriptor.get("name"));if(type==null)HistoryCodec.invalid("Missing historical declaration");return type;}
        @Override public Set<Entry<String,Map<String,Object>>> entrySet(){return new AbstractSet<>(){public int size(){return rows.size();}public Iterator<Entry<String,Map<String,Object>>> iterator(){var iterator=rows.keySet().iterator();return new Iterator<>(){public boolean hasNext(){return iterator.hasNext();}public Entry<String,Map<String,Object>> next(){String key=iterator.next();return new SimpleImmutableEntry<>(key,get(key));}};}};}
    }
    private Map<String,Object> portableImage(Map<String,Map<String,Object>> state,Map<String,Object> image){if(image==null)return null;var out=new LinkedHashMap<>(image);if("Descriptor".equals(image.get("kind")))out.put("value",ExchangePackageCodec.encodeValue(HistoryCodec.descriptorType(state,image),image.get("value")));return out;}
    private Node root(){return repo.namespace(c.namespaceId);}
    private void enabled(){if("disabled".equals(root().getProperty("historyMode")))c.fail("HISTORY_UNAVAILABLE","History is disabled");}
    private void read(String role){readContext();scoped(role);}
    private long generation(){return ((Number)root().getProperty("historyAvailability",0L)).longValue();}
    private void stable(long sequence,long generation){c.state.kernel.assertOpen();if(System.currentTimeMillis()-c.state.kernel.startTime()>30000)c.fail("CANCELLED","Read timeout exceeded");authenticate();requireRole(c.namespaceId,"viewer");if(repo.sequence(c.namespaceId)!=sequence||generation()!=generation)c.fail("REVISION_CONFLICT","History changed during read");}
    public DataRow revision(Map<String,Object> request){fields(request);read("viewer");return new DataRow(Json.map("sequence",c.revision,"revisionId",root().getProperty("headRevisionId",null),"historyMode",root().getProperty("historyMode"),"historyEpochId",root().getProperty("historyEpochId",null)),c.meta());}
    public ValueRow restored(Map<String,Object> request){fields(request);read("viewer");Object head=root().getProperty("headRevisionId",null);return new ValueRow(head!=null&&history.revision(c.namespaceId,(String)head).kind().equals("restore"),c.meta());}
    public Stream<DataRow> records(Map<String,Object> request){fields(request);read("viewer");long sequence=c.revision,generation=generation();var values=history.revisions(c.namespaceId);stable(sequence,generation);return values.stream().map(r->{c.server.storagePoint("history-read-row");stable(sequence,generation);return new DataRow(r.data(),c.meta());}).onClose(()->stable(sequence,generation));}
    public DataRow page(Map<String,Object> request){
        fields(request,"pageSize","cursor");read("viewer");long sequence=c.revision,generation=generation();long limit=integer(request.getOrDefault("pageSize",200L));if(limit<1||limit>1000)c.fail("LIMIT_EXCEEDED","Page size must be 1..1000");int offset=0;
        if(request.containsKey("cursor")){var cursor=CursorCodec.decode(c,string(request.get("cursor")));if(!c.namespaceId.equals(cursor.get("namespaceId"))||!c.actorId.equals(cursor.get("actorId"))||!"history".equals(cursor.get("kind")))c.fail("SCOPE_MISMATCH","Cursor scope differs");if(sequence!=((Number)cursor.get("sequence")).longValue()||generation!=((Number)cursor.get("generation")).longValue())c.fail("REVISION_CONFLICT","History cursor changed");offset=((Number)cursor.get("offset")).intValue();}
        var all=history.revisions(c.namespaceId);if(offset<0||offset>all.size())c.fail("VALIDATION_FAILED","Invalid history offset");int end=(int)Math.min(all.size(),offset+limit);String next=end==all.size()?null:CursorCodec.encode(c,Json.map("kind","history","namespaceId",c.namespaceId,"actorId",c.actorId,"sequence",sequence,"generation",generation,"offset",end,"expires",System.currentTimeMillis()+900000));stable(sequence,generation);var meta=c.meta();meta.put("nextCursor",next);return new DataRow(Json.map("items",all.subList(offset,end).stream().map(Revision::data).toList()),meta);
    }
    private List<Revision> epoch(String id){return history.revisions(c.namespaceId).stream().filter(r->id.equals(r.data().get("historyEpochId"))).toList();}
    private Revision select(String id,boolean redo,boolean inspection){
        enabled();String epochId=(String)root().getProperty("historyEpochId");var revisions=epoch(epochId);Revision selected=null;
        if(id!=null)selected=history.revision(c.namespaceId,id);
        else for(var r:revisions)if(c.actorId.equals(r.data().get("actorId"))&&HistoryPlanner.eligible(r,revisions,false))selected=r;
        if(selected==null)c.fail("NOT_FOUND","No undo candidate");
        if(!epochId.equals(selected.data().get("historyEpochId")))c.fail("HISTORY_UNAVAILABLE","Revision belongs to a closed epoch");
        if(!inspection&&!c.actorId.equals(selected.data().get("actorId"))&&!"owner".equals(effectiveRole(c.namespaceId)))c.fail("FORBIDDEN","Only owner may reverse another actor's action");
        if(!HistoryPlanner.eligible(selected,revisions,redo))c.fail("REVISION_CONFLICT","Revision effect is not eligible for reversal");return selected;
    }
    private UndoPlan inverse(String id,boolean redo,boolean inspection){Revision selected=select(id,redo,inspection);var revisions=epoch((String)selected.data().get("historyEpochId"));HistoryPlanner.reconstruct(revisions,selected.id());var plan=HistoryPlanner.inverse(c.revision,history.capture(c.namespaceId),selected,revisions);if(plan.conflicts().isEmpty())history.validateResources(c.namespaceId,plan.result());return plan;}
    public UndoPlan previewUndo(String id){return inverse(id,false,false);}
    public RestorePlan previewRestore(String id){enabled();requireRole(c.namespaceId,"owner");Revision target=history.revision(c.namespaceId,id);var plan=HistoryPlanner.restore(c.revision,history.capture(c.namespaceId),HistoryPlanner.reconstruct(epoch((String)target.data().get("historyEpochId")),id),id);if(plan.plan().conflicts().isEmpty())history.validateResources(c.namespaceId,plan.plan().result());return plan;}
    public DataRow restorePreview(Map<String,Object> request){fields(request,"targetRevisionId");read("owner");String id=RequestContext.uuid(request.get("targetRevisionId"));long generation=generation();var plan=previewRestore(id);stable(c.revision,generation);return new DataRow(plan.data(),c.meta());}
    public CanUndoRow canUndo(Map<String,Object> request){fields(request,"revisionId");read("viewer");String id=request.containsKey("revisionId")?RequestContext.uuid(request.get("revisionId")):null;long sequence=c.revision,generation=generation();
        try{var plan=inverse(id,false,true);stable(sequence,generation);return new CanUndoRow(plan.conflicts().isEmpty(),plan.data(),c.meta());}
        catch(ExceptionHandler.DepositException e){var error=Json.read(e.getMessage().substring("DEPOSIT_ERROR ".length()));String code=(String)error.get("code");if(!Set.of("NOT_FOUND","HISTORY_UNAVAILABLE","REVISION_CONFLICT","RESOURCE_CORRUPT").contains(code))throw e;stable(sequence,generation);return new CanUndoRow(false,Json.map("targetRevisionId",id,"reason",code,"conflicts",List.of(Json.map("reason",code))),c.meta());}
    }
    public Outcome reverse(String kind,Map<String,Object> request){String key=kind.equals("restore")?"targetRevisionId":kind.equals("redo")?"undoRevisionId":"revisionId";fields(request,key);String id=request.containsKey(key)?RequestContext.uuid(request.get(key)):null;if(id==null&&!kind.equals("undo"))c.fail("VALIDATION_FAILED",key+" required");
        return action("deposit.utils."+kind,request,false,kind.equals("restore")?"owner":"editor",()->{
            UndoPlan plan=kind.equals("restore")?previewRestore(id).plan():inverse(id,kind.equals("redo"),false);
            if(!plan.conflicts().isEmpty())throw ExceptionHandler.error("REVISION_CONFLICT","History plan conflicts",c,false,Json.map("conflicts",plan.conflicts()));
            if(kind.equals("restore")&&plan.changes().touches.isEmpty()){
                var data=plan.data();data.put("kind",kind);data.put("changed",false);
                return new Change(new Outcome(null,null,data),false);
            }
            history.apply(c.namespaceId,c.actorId,plan);c.historyKind=kind;c.historyLink=kind.equals("restore")?"restoresRevisionId":kind.equals("redo")?"reappliesRevisionId":"reversesRevisionId";c.historyTarget=plan.targetId();
            if(!kind.equals("restore")){Revision selected=history.revision(c.namespaceId,plan.targetId());selected.touches().forEach((entity,fields)->c.journal.touches.computeIfAbsent(entity,k->new TreeSet<>()).addAll(fields));c.journal.dependencies.addAll(selected.dependencies());}
            var data=plan.data();data.put("kind",kind);data.put("changed",true);return new Change(new Outcome(null,null,data),true);
        });
    }
    public Outcome mode(Map<String,Object> request){fields(request,"mode");String mode=string(request.get("mode"));if(!Set.of("disabled","transient","persistent").contains(mode))c.fail("VALIDATION_FAILED","Invalid history mode");return action("deposit.set.versioningType",request,false,"owner",()->{boolean changed=!mode.equals(root().getProperty("historyMode"));if(changed)history.mode(c.namespaceId,mode,c.actorId,c.revision+1,c.server.runId,c.timestamp);return new Change(new Outcome(null,null,Json.map("historyMode",mode,"historyEpochId",root().getProperty("historyEpochId",null),"revisionId",root().getProperty("headRevisionId",null))),changed);});}
    public Outcome tag(Map<String,Object> request){fields(request,"revisionId","tag");String id=RequestContext.uuid(request.get("revisionId")),tag=string(request.get("tag"));return action("deposit.set.versionTag",request,false,"owner",()->new Change(new Outcome(null,null,Json.map("revisionId",id,"tag",tag)),history.tag(c.namespaceId,id,tag,c.actorId,c.timestamp)));}
    public static final class CanUndoRow {public final boolean value;public final Map<String,Object> data,meta;public CanUndoRow(boolean value,Map<String,Object> data,Map<String,Object> meta){this.value=value;this.data=data;this.meta=meta;}}
}
