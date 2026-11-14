package deposit.common;

import java.io.*;
import java.util.*;

/** Validates complete history bundles against their portable graph and declaration state. */
final class PackageHistoryValidator {
    private PackageHistoryValidator() {}
    private static boolean baselineEquals(Map<String,Object> baseline,Map<String,Object> first,PackageWorkspace workspace)throws IOException{
        if(!baseline.keySet().equals(first.keySet()))return false;
        for(String key:baseline.keySet())if(!key.equals("touches")&&!Objects.equals(baseline.get(key),first.get(key)))return false;
        if(!(baseline.get("touches") instanceof Map<?,?> left)||!(first.get("touches") instanceof Map<?,?> right))return false;
        int mark=workspace==null?0:workspace.mark();Map<String,Object> indexed=workspace==null?new HashMap<>():workspace.map();
        try{
            for(var entry:right.entrySet()){String key=PackageIdentity.qualified(entry.getKey());if(indexed.containsKey(key))throw new IOException("Duplicate canonical journal identity");indexed.put(key,entry.getValue());}
            for(var entry:left.entrySet()){String key=PackageIdentity.qualified(entry.getKey());if(!indexed.containsKey(key)||!Objects.equals(indexed.remove(key),entry.getValue()))return false;}
            return indexed.isEmpty();
        }finally{if(workspace!=null)workspace.release(mark);}
    }
    static void validate(Map<String,Object> history,Map<String,Object> graph,boolean wholeNamespace,PackageValidationContext context)throws IOException{
        PackageWorkspace workspace=context.workspace;
        if(!wholeNamespace)throw new IOException("Subgraph packages cannot contain history");
        if(!history.keySet().equals(Set.of("namespaceId","historyMode","activeEpochId","headRevisionId","epochs","complete")))throw new IOException("Unknown or missing history bundle fields");
        try{PackageIdentity.canonical((String)history.get("namespaceId"));PackageIdentity.canonical((String)history.get("activeEpochId"));PackageIdentity.canonical((String)history.get("headRevisionId"));}catch(RuntimeException e){throw new IOException("Invalid history provenance identity");}
        if(!"persistent".equals(history.get("historyMode"))||!Boolean.TRUE.equals(history.get("complete"))||!(history.get("epochs") instanceof List<?> epochs)||epochs.isEmpty())throw new IOException("History bundle is incomplete");
        Set<String> epochIds=workspace==null?new HashSet<>():workspace.set(),revisionIds=workspace==null?new HashSet<>():workspace.set();String activeHead=null;boolean foundActive=false;
        for(Object raw:epochs){var epoch=ValueCodec.map(raw);if(!epoch.keySet().equals(Set.of("id","mode","closed","startedSequence","endedSequence","baselineRevisionId","headRevisionId","baseline","revisions")))throw new IOException("Unknown or missing history epoch fields");
            String id=(String)epoch.get("id");try{PackageIdentity.canonical(id);}catch(RuntimeException e){throw new IOException("Invalid history epoch identity");}if(!epochIds.add(id)||!Set.of("persistent","transient").contains(epoch.get("mode"))||!(epoch.get("closed") instanceof Boolean))throw new IOException("Invalid or duplicate history epoch");
            PackageValueCodec.metadataInteger(epoch.get("startedSequence"));if(epoch.get("endedSequence")!=null)PackageValueCodec.metadataInteger(epoch.get("endedSequence"));
            if(!(epoch.get("revisions") instanceof List<?> revisions)||revisions.isEmpty()||!(epoch.get("baseline") instanceof Map<?,?> baseline))throw new IOException("History epoch is incomplete");
            if(!Objects.equals(epoch.get("baselineRevisionId"),ValueCodec.map(baseline).get("revisionId"))||!Objects.equals(epoch.get("headRevisionId"),ValueCodec.map(revisions.getLast()).get("revisionId")))throw new IOException("History epoch bounds differ from its revisions");
            var single=Json.map("namespaceId",history.get("namespaceId"),"historyEpochId",id,"baseline",baseline,"revisions",revisions,"complete",true);
            validateHistoryEpoch(single,graph,true,workspace);
            for(Object rr:revisions){String revision=(String)ValueCodec.map(rr).get("revisionId");if(!revisionIds.add(revision))throw new IOException("Duplicate revision identity across history epochs");}
            if(id.equals(history.get("activeEpochId"))){foundActive=true;activeHead=(String)epoch.get("headRevisionId");if(Boolean.TRUE.equals(epoch.get("closed")))throw new IOException("Active history epoch is closed");}
        }
        if(!foundActive||!Objects.equals(activeHead,history.get("headRevisionId")))throw new IOException("Active history head is missing or inconsistent");
    }
    private static void validateHistoryEpoch(Map<String,Object> history,Map<String,Object> graph,boolean wholeNamespace,PackageWorkspace workspace)throws IOException{
        if(!history.keySet().equals(Set.of("namespaceId","historyEpochId","baseline","revisions","complete")))throw new IOException("Unknown or missing history epoch bundle fields");
        try{PackageIdentity.canonical((String)history.get("namespaceId"));PackageIdentity.canonical((String)history.get("historyEpochId"));}catch(RuntimeException e){throw new IOException("Invalid history provenance identity");}
        if(!Boolean.TRUE.equals(history.get("complete"))||!(history.get("revisions") instanceof List<?> revisions)||revisions.isEmpty()||!(history.get("baseline") instanceof Map<?,?> baseline))throw new IOException("History bundle is incomplete");
        if(!baselineEquals(ValueCodec.map(baseline),ValueCodec.map(revisions.getFirst()),workspace)||!"baseline".equals(ValueCodec.map(revisions.getFirst()).get("kind")))throw new IOException("History baseline does not match its first revision");
        Map<String,Map<String,Object>> rawState=workspace==null?new HashMap<>():workspace.map();
        Map<String,String> memberships=workspace==null?new HashMap<>():workspace.map(),declarations=workspace==null?new HashMap<>():workspace.map();
        var state=new WireState(rawState,memberships,declarations);
        Set<String> resourceIds=workspace==null?new HashSet<>():workspace.set(),revisionIds=workspace==null?new HashSet<>():workspace.set();
        for(Object raw:(List<?>)graph.get("resources"))resourceIds.add((String)ValueCodec.map(raw).get("id"));
        long previous=-1;String epoch=(String)history.get("historyEpochId"),parent=null;
        for(Object rawRevision:revisions){
            var revision=ValueCodec.map(rawRevision);
            if(!Set.of("id","revisionId","namespaceId","sequence","historyEpochId","historyMode","operationId","actorId","committedAt","kind","summary","parentRevisionId","changes","touches","dependencies","tag","epoch","reversesRevisionId","reappliesRevisionId","restoresRevisionId").containsAll(revision.keySet()))throw new IOException("Nonportable history metadata");
            if(!Objects.equals(history.get("namespaceId"),revision.get("namespaceId")))throw new IOException("History revision escaped source Namespace");
            if(!Objects.equals(epoch,revision.get("historyEpochId"))||!(revision.get("changes") instanceof List<?> rows)||!(revision.get("revisionId") instanceof String revisionId)||!revisionIds.add(revisionId))throw new IOException("History epoch or changes are invalid");
            try{PackageIdentity.canonical(revisionId);}catch(RuntimeException e){throw new IOException("Invalid revision UUID",e);}
            if(!Objects.equals(parent,revision.get("parentRevisionId")))throw new IOException("History parent chain is incomplete");
            parent=revisionId;PackageValueCodec.metadataInteger(revision.get("sequence"));long sequence=((Number)revision.get("sequence")).longValue();if(sequence<=previous)throw new IOException("History revisions are out of order");previous=sequence;
            if(revision.get("epoch") instanceof Map<?,?> epochRecord&&!Set.of("id","mode","closed","baselineRevisionId").containsAll(epochRecord.keySet()))throw new IOException("Nonportable epoch metadata");
            int journalMark=workspace==null?0:workspace.mark();Set<String> journalIds=workspace==null?new HashSet<>():workspace.set();
            try{if(!(revision.get("touches") instanceof Map<?,?> touches)||!(revision.get("dependencies") instanceof List<?> dependencies))throw new IOException("Invalid history journal");for(Object identity:touches.keySet())if(!journalIds.add(PackageIdentity.qualified(identity)))throw new IOException("Duplicate canonical journal identity");for(Object identity:dependencies)PackageIdentity.qualified(identity);}finally{if(workspace!=null)workspace.release(journalMark);}
            Map<String,Map<String,Object>> changes=workspace==null?new HashMap<>():workspace.map();
            try{
                for(Object raw:rows){var row=ValueCodec.map(raw);String kind=(String)row.get("entityKind"),id=(String)row.get("entityId"),key=HistoryCodec.key(kind,PackageIdentity.canonical(id));
                    if(!HistoryCodec.KINDS.contains(kind)||!wholeNamespace&&"UserTool".equals(kind))throw new IOException("Invalid history image kind or scope");
                    if(changes.put(key,row)!=null)throw new IOException("Duplicate history change identity");
                    Map<String,Object> before=row.get("before") instanceof Map<?,?> b?ValueCodec.map(b):null,after=row.get("after") instanceof Map<?,?> a?ValueCodec.map(a):null;
                    if(before!=null&&!key.equals(HistoryCodec.key(before))||after!=null&&!key.equals(HistoryCodec.key(after)))throw new IOException("History image identity mismatch");
                    if(!Objects.equals(state.get(key),decodeHistoryImage(state,before)))throw new IOException("History before image differs from preceding state");
                }
                // All before-images were checked against one unchanged preceding state.
                for(var change:changes.entrySet()){Object after=change.getValue().get("after");if(after==null)rawState.remove(change.getKey());else rawState.put(change.getKey(),ValueCodec.map(after));}
                memberships.clear();declarations.clear();
                for(var image:rawState.values()){if("Membership".equals(image.get("kind"))){if(memberships.put((String)image.get("objectId"),(String)image.get("classId"))!=null)throw new IOException("Multiple historical memberships");}else if("ClassDescriptor".equals(image.get("kind"))){if(declarations.put(image.get("classId")+"\u0000"+image.get("name"),ValueCodec.type(image.get("type")))!=null)throw new IOException("Duplicate historical declaration");}}
                int mark=workspace==null?0:workspace.mark();try{HistoryCodec.validate(state,workspace,true);}finally{if(workspace!=null)workspace.release(mark);}
                for(var image:state.values()){
                    try{if(!PackageIdentity.canonical((String)image.get("id")).equalsIgnoreCase((String)image.get("id")))throw new IllegalArgumentException();}catch(RuntimeException bad){throw new IOException("Invalid historical UUID",bad);}
                    if(!Objects.equals(history.get("namespaceId"),image.get("namespaceId"))||"Namespace".equals(image.get("kind"))&&!Objects.equals(history.get("namespaceId"),image.get("id")))throw new IOException("Historical image escaped source Namespace");
                    if("Descriptor".equals(image.get("kind"))&&image.get("value")!=null&&"DResource".equals(state.descriptorType(image))&&!resourceIds.contains(ValueCodec.map(image.get("value")).get("resourceId")))throw new IOException("Historical resource is missing");
                }
            }finally{if(changes instanceof PackageWorkspace.DiskMap<?> disk)disk.dispose();}
        }
    }
    private static final class WireState extends AbstractMap<String,Map<String,Object>> implements HistoryCodec.DeclarationState {
        final Map<String,Map<String,Object>> raw;final Map<String,String> memberships,declarations;
        WireState(Map<String,Map<String,Object>> raw,Map<String,String> memberships,Map<String,String> declarations){this.raw=raw;this.memberships=memberships;this.declarations=declarations;}
        public String descriptorType(Map<String,Object> descriptor){String clazz=memberships.get(descriptor.get("objectId"));String type=clazz==null?null:declarations.get(clazz+"\u0000"+descriptor.get("name"));if(type==null)HistoryCodec.invalid("Missing historical declaration");return type;}
        @Override public Map<String,Object> get(Object key){var image=raw.get(key);try{return decodeHistoryImage(this,image);}catch(IOException e){throw new UncheckedIOException(e);}}
        @Override public Set<Entry<String,Map<String,Object>>> entrySet(){return new AbstractSet<>(){public int size(){return raw.size();}public Iterator<Entry<String,Map<String,Object>>> iterator(){var iterator=raw.entrySet().iterator();return new Iterator<>(){public boolean hasNext(){return iterator.hasNext();}public Entry<String,Map<String,Object>> next(){var row=iterator.next();return new SimpleImmutableEntry<>(row.getKey(),WireState.this.get(row.getKey()));}};}};}
    }
    private static Map<String,Object> decodeHistoryImage(Map<String,Map<String,Object>> state,Map<String,Object> image)throws IOException{if(image==null)return null;var out=new LinkedHashMap<>(image);if("Descriptor".equals(image.get("kind"))){String type=HistoryCodec.descriptorType(state,image);out.put("value",PackageValueCodec.validateValue(type,image.get("value"),true,0));}return out;}
}
