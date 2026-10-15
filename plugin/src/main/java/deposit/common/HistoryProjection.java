package deposit.common;

import java.util.*;

/** Fixed live identity scope with the historical schema and content needed to reconstruct it. */
public final class HistoryProjection {
    public record Projection(Map<String,Object> history,Set<String> resourceIds) {}
    private HistoryProjection() {}

    public static Projection project(Map<String,Object> source,Set<String> objects,Set<String> patterns,boolean wholeNamespace) {
        return project(source,objects,patterns,wholeNamespace,null);
    }
    public static Projection project(Map<String,Object> source,Set<String> objects,Set<String> patterns,boolean wholeNamespace,ExportRecords records) {
        Set<String> selected=records==null?new TreeSet<>():Collections.newSetFromMap(records.<Boolean>index()),resources=records==null?new TreeSet<>():Collections.newSetFromMap(records.<Boolean>index());
        if(wholeNamespace&&source.get("epochs") instanceof List<?> epochs){
            for(Object rawEpoch:epochs){
                int mark=records==null?0:records.indexMark();
                try {
                    Map<String,Map<String,Object>> epochState=records==null?new TreeMap<>():records.index();
                    Map<String,String> memberships=records==null?new HashMap<>():records.index(),declarations=records==null?new HashMap<>():records.index();
                    for(Object rawRevision:(List<?>)ValueCodec.map(rawEpoch).get("revisions")){
                        for(Object rawChange:(List<?>)ValueCodec.map(rawRevision).get("changes")){
                            var change=ValueCodec.map(rawChange);String key=HistoryCodec.key((String)change.get("entityKind"),(String)change.get("entityId"));if(change.get("after")==null)epochState.remove(key);else epochState.put(key,ValueCodec.map(change.get("after")));
                        }
                        // Each retained revision must be reconstructable, including resources later cleared or retyped.
                        memberships.clear();declarations.clear();
                        for(var image:epochState.values())if("Membership".equals(image.get("kind")))memberships.put((String)image.get("objectId"),(String)image.get("classId"));else if("ClassDescriptor".equals(image.get("kind")))declarations.put(image.get("classId")+"\u0000"+image.get("name"),(String)image.get("type"));
                        for(var image:epochState.values())if("Descriptor".equals(image.get("kind"))&&image.get("value")!=null&&"DResource".equals(declarations.get(memberships.get(image.get("objectId"))+"\u0000"+image.get("name"))))resources.add((String)ValueCodec.map(image.get("value")).get("resourceId"));
                    }
                } finally {
                    if(records!=null)records.releaseIndexes(mark);
                }
            }
            return new Projection(source,Collections.unmodifiableSet(resources));
        }
        Map<String,Map<String,Object>> state=records==null?new TreeMap<>():records.index();
        for(Object raw:(List<?>)source.get("revisions")) {
            var revision=ValueCodec.map(raw);
            for(Object rawChange:(List<?>)revision.get("changes")) {
                var change=ValueCodec.map(rawChange);String key=HistoryCodec.key((String)change.get("entityKind"),(String)change.get("entityId"));
                if(change.get("after")==null)state.remove(key);else state.put(key,ValueCodec.map(change.get("after")));
            }
            int mark=records==null?0:records.indexMark();Set<String> scope=wholeNamespace?state.keySet():scope(state,objects,patterns,records);
            Map<String,String> memberships=records==null?new HashMap<>():records.index(),declarations=records==null?new HashMap<>():records.index();
            for(var image:state.values())if("Membership".equals(image.get("kind")))memberships.put((String)image.get("objectId"),(String)image.get("classId"));else if("ClassDescriptor".equals(image.get("kind")))declarations.put(image.get("classId")+"\u0000"+image.get("name"),(String)image.get("type"));
            selected.addAll(scope);
            for(String key:scope) {
                var image=state.get(key);
                if(image!=null&&"Descriptor".equals(image.get("kind"))&&image.get("value")!=null&&"DResource".equals(declarations.get(memberships.get(image.get("objectId"))+"\u0000"+image.get("name"))))
                    resources.add((String)ValueCodec.map(image.get("value")).get("resourceId"));
            }
            if(records!=null)records.releaseIndexes(mark);
        }
        if(wholeNamespace)return new Projection(source,Collections.unmodifiableSet(resources));
        List<Map<String,Object>> revisions=records==null?new ArrayList<>():records.revisions("projected-revisions");
        for(Object raw:(List<?>)source.get("revisions")) {
            int mark=records==null?0:records.indexMark();var revision=new LinkedHashMap<>(ValueCodec.map(raw));
            if(records==null)revision.put("changes",((List<?>)revision.get("changes")).stream().filter(row->{var change=ValueCodec.map(row);return selected.contains(HistoryCodec.key((String)change.get("entityKind"),(String)change.get("entityId")));}).toList());
            else{int start=records.list("projected-changes").size();for(Object row:(List<?>)revision.get("changes")){var change=ValueCodec.map(row);if(selected.contains(HistoryCodec.key((String)change.get("entityKind"),(String)change.get("entityId"))))add(records,"projected-changes",change);}revision.put("changes",records.slice("projected-changes",start,records.list("projected-changes").size()));}
            if(revision.get("touches") instanceof Map<?,?>) {
                Map<String,Object> touches=records==null?new TreeMap<>():records.index();ValueCodec.map(revision.get("touches")).forEach((key,value)->{if(selected.contains(key))touches.put(key,value);});revision.put("touches",touches);
            }
            if(revision.get("dependencies") instanceof Collection<?> dependencies){if(records==null)revision.put("dependencies",dependencies.stream().filter(selected::contains).toList());else{int start=records.list("projected-dependencies").size();for(Object value:dependencies)if(selected.contains(value))try{records.addValue("projected-dependencies",value);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}revision.put("dependencies",records.valuesSlice("projected-dependencies",start,records.list("projected-dependencies").size()));}}
            if(records==null)revisions.add(revision);else{try{records.addRevision("projected-revisions",revision);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}records.releaseIndexes(mark);}
        }
        if(records!=null)revisions=records.revisions("projected-revisions");var history=new LinkedHashMap<>(source);history.put("revisions",revisions);history.put("baseline",revisions.getFirst());
        return new Projection(history,Collections.unmodifiableSet(resources));
    }

    private static void add(ExportRecords records,String category,Map<String,Object> value){try{records.add(category,value);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
    private static Set<String> scope(Map<String,Map<String,Object>> state,Set<String> objects,Set<String> patterns,ExportRecords records) {
        Set<String> keys=records==null?new TreeSet<>():Collections.newSetFromMap(records.<Boolean>index()),classes=records==null?new TreeSet<>():Collections.newSetFromMap(records.<Boolean>index()),descriptors=records==null?new TreeSet<>():Collections.newSetFromMap(records.<Boolean>index());
        Map<String,String> classesByName=records==null?new HashMap<>():records.index();
        for(var image:state.values())if("Class".equals(image.get("kind")))classesByName.put((String)image.get("name"),(String)image.get("id"));
        for(var image:state.values()) {
            String kind=(String)image.get("kind"),id=(String)image.get("id");
            switch(kind) {
                case "Namespace"->keys.add(HistoryCodec.key(image));
                case "Object"->{if(objects.contains(id))keys.add(HistoryCodec.key(image));}
                case "Pattern"->{if(patterns.contains(id)){keys.add(HistoryCodec.key(image));for(Object raw:ValueCodec.map(ValueCodec.map(image.get("definition")).get("objects")).values()){String clazz=classesByName.get(ValueCodec.map(raw).get("className"));if(clazz==null)HistoryCodec.invalid("Historical Pattern Class dependency is missing");classes.add(clazz);}}}
                case "Membership"->{if(objects.contains(image.get("objectId"))){keys.add(HistoryCodec.key(image));classes.add((String)image.get("classId"));}}
                case "Descriptor"->{if(objects.contains(image.get("objectId"))){keys.add(HistoryCodec.key(image));descriptors.add(id);}}
                case "Relationship"->{if(objects.contains(image.get("sourceId"))&&objects.contains(image.get("targetId")))keys.add(HistoryCodec.key(image));}
                default->{}
            }
        }
        for(var image:state.values()) {
            String kind=(String)image.get("kind");
            if(kind.equals("Class")&&classes.contains(image.get("id"))||kind.equals("ClassDescriptor")&&classes.contains(image.get("classId"))||kind.equals("DGeometry")&&descriptors.contains(image.get("descriptorId"))||kind.equals("ClassRelationship")&&classes.contains(image.get("sourceId"))&&classes.contains(image.get("targetId")))keys.add(HistoryCodec.key(image));
        }
        return keys;
    }
}
