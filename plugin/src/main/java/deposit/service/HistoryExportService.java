package deposit.service;

import deposit.common.*;
import deposit.repository.*;
import java.util.*;
import org.neo4j.graphdb.Node;

/** Assembles portable retained-history exports inside the caller's transaction. */
final class HistoryExportService extends BaseService {
    private final HistoryRepository history;
    HistoryExportService(RequestContext c){super(c);history=new HistoryRepository(c.tx,repo);}
    private Node root(){return repo.namespace(c.namespaceId);}
    private void enabled(){if("disabled".equals(root().getProperty("historyMode")))c.fail("HISTORY_UNAVAILABLE","History is disabled");}
    /** Immutable provenance bundle for exchange; snapshots remain source history, never local revisions. */
    public Map<String,Object> historyExport(){
        enabled();requireRole(c.namespaceId,"owner");String epochId=(String)root().getProperty("historyEpochId");Node epoch=history.epoch(c.namespaceId,epochId);var revisions=history.revisions(c.namespaceId).stream().filter(r->epochId.equals(r.data().get("historyEpochId"))).toList();
        if(epoch==null||revisions.isEmpty()||!Objects.equals(epoch.getProperty("baselineRevisionId"),revisions.getFirst().id()))c.fail("HISTORY_UNAVAILABLE","History epoch has no reconstructable baseline");
        String head=(String)root().getProperty("headRevisionId",null);if(head==null)c.fail("HISTORY_UNAVAILABLE","History head is unavailable");HistoryPlanner.reconstruct(revisions,head);
        var portable=new ArrayList<Map<String,Object>>();var state=new TreeMap<String,Map<String,Object>>();for(Revision revision:revisions){var data=new LinkedHashMap<>(revision.data());data.remove("bootId");var after=new TreeMap<>(state);revision.after().forEach((key,image)->after.put(key,image));revision.before().keySet().stream().filter(key->!revision.after().containsKey(key)).forEach(after::remove);
            var rows=new ArrayList<Map<String,Object>>();for(Object raw:(List<?>)data.get("changes")){var row=new LinkedHashMap<>(ValueCodec.map(raw));String kind=(String)row.get("entityKind"),id=(String)row.get("entityId"),key=HistoryCodec.key(kind,id);Map<String,Object> before=state.get(key),next=after.get(key);row.put("before",portableImage(state,before));row.put("after",portableImage(after,next));rows.add(row);}data.put("changes",rows);portable.add(data);state=after;}
        return Json.map("namespaceId",c.namespaceId,"historyEpochId",epochId,"baseline",portable.getFirst(),"revisions",portable,"complete",true);
    }
    /** Export every retained, unexpired epoch for a persistent Namespace. */
    public Map<String,Object> historyExport(ExportRecords records){
        if(!"persistent".equals(root().getProperty("historyMode")))c.fail("HISTORY_UNAVAILABLE","Only persistent Namespace history is exported");
        String active=(String)root().getProperty("historyEpochId",null),head=(String)root().getProperty("headRevisionId",null);
        if(active==null||head==null)c.fail("HISTORY_UNAVAILABLE","Active history head is unavailable");
        var epochs=new ArrayList<Map<String,Object>>();
        for(Node epoch:repo.nodes("DepositHistoryEpoch",Map.of("namespaceId",c.namespaceId)))if(!Boolean.TRUE.equals(epoch.getProperty("expired",false)))epochs.add(historyExportEpoch(records,epoch,CoreRepository.id(epoch).equals(active)));
        epochs.sort(Comparator.comparingLong(e->((Number)e.getOrDefault("startedSequence",0L)).longValue()));
        Map<String,Object> activeEpoch=epochs.stream().filter(e->active.equals(e.get("id"))).findFirst().orElse(null);
        if(activeEpoch==null||!head.equals(activeEpoch.get("headRevisionId")))c.fail("HISTORY_UNAVAILABLE","Active history epoch is missing or inconsistent");
        return Json.map("namespaceId",c.namespaceId,"historyMode","persistent","activeEpochId",active,"headRevisionId",head,"epochs",epochs,"complete",true);
    }
    /** Export one epoch through fixed record spools, retaining only the current image index. */
    private Map<String,Object> historyExportEpoch(ExportRecords records,Node epoch,boolean active){
        String epochId=CoreRepository.id(epoch);
        int revisionStart=records.list("history-revisions").size();
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
        if(first==null)c.fail("HISTORY_UNAVAILABLE","History baseline is unavailable");
        if(active&&!Objects.equals(previous,root().getProperty("headRevisionId",null)))c.fail("HISTORY_UNAVAILABLE","History head is unavailable");
        if(!active&&epoch.hasProperty("endedSequence")&&sequence>((Number)epoch.getProperty("endedSequence")).longValue())c.fail("HISTORY_UNAVAILABLE","Closed history epoch exceeds its end sequence");
        var revisions=records.revisions("history-revisions",revisionStart,records.list("history-revisions").size());
        return Json.map("id",epochId,"mode",epoch.getProperty("mode"),"closed",epoch.getProperty("closed",false),"startedSequence",epoch.getProperty("startedSequence",0L),"endedSequence",epoch.getProperty("endedSequence",null),"baselineRevisionId",epoch.getProperty("baselineRevisionId"),"headRevisionId",previous,"baseline",revisions.getFirst(),"revisions",revisions);
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
}
