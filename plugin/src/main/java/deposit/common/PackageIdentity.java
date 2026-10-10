package deposit.common;

import java.util.*;

/** UUID comparison views; never changes package payload or scientific strings. */
final class PackageIdentity {
    private PackageIdentity() {}
    private static final Set<String> FIELDS=Set.of("id","namespaceId","classId","objectId","descriptorId","geometryId","sourceId","targetId","resourceId","entityId","revisionId","parentRevisionId","historyEpochId","baselineRevisionId","reversesRevisionId","reappliesRevisionId","restoresRevisionId","sourceNamespaceId","packageId","createdBy","updatedBy","actorId","operationId");
    static String canonical(String value) {
        String canonical=UUID.fromString(value).toString();
        if(!canonical.equalsIgnoreCase(value))throw new IllegalArgumentException("Expected full UUID");
        return canonical;
    }
    static String qualified(Object value){
        if(!(value instanceof String s))throw new IllegalArgumentException("Expected category UUID");
        int split=s.indexOf('\0');if(split<1||!HistoryCodec.KINDS.contains(s.substring(0,split)))throw new IllegalArgumentException("Invalid identity category");
        return s.substring(0,split+1)+canonical(s.substring(split+1));
    }
    static Object field(String key,Object value,boolean journal) {
        if(FIELDS.contains(key)&&value instanceof String s)try{return canonical(s);}catch(IllegalArgumentException ignored){}
        if(journal&&key.equals("touches")&&value instanceof Map<?,?> map)return new AbstractMap<String,Object>() {
            public Set<Entry<String,Object>> entrySet(){return new AbstractSet<>() {
                public int size(){return map.size();}
                public Iterator<Entry<String,Object>> iterator(){var iterator=map.entrySet().iterator();return new Iterator<>(){public boolean hasNext(){return iterator.hasNext();}public Entry<String,Object> next(){var entry=iterator.next();return new SimpleImmutableEntry<>(qualified(entry.getKey()),entry.getValue());}};}
            };}
            public Object get(Object key){if(map.containsKey(key))return map.get(key);for(var entry:entrySet())if(entry.getKey().equals(key))return entry.getValue();return null;}
        };
        if(journal&&key.equals("dependencies")&&value instanceof List<?> list)return new AbstractList<Object>() {
            public int size(){return list.size();}public Object get(int i){return qualified(list.get(i));}
            public Iterator<Object> iterator(){var iterator=list.iterator();return new Iterator<>(){public boolean hasNext(){return iterator.hasNext();}public Object next(){return qualified(iterator.next());}};}
        };
        if(key.equals("rootIds")&&value instanceof List<?> list)return new AbstractList<Object>() {
            public int size(){return list.size();}
            public Object get(int i){Object v=list.get(i);return v instanceof String s?canonical(s):v;}
            public Iterator<Object> iterator(){var iterator=list.iterator();return new Iterator<>(){public boolean hasNext(){return iterator.hasNext();}public Object next(){Object v=iterator.next();return v instanceof String s?canonical(s):v;}};}
        };
        return value;
    }
    @SuppressWarnings("unchecked") static Map<String,Object> view(Map<String,Object> source){return (Map<String,Object>)wrap(source);}
    private static Object wrap(Object value) {
        if(value instanceof Map<?,?> map)return new AbstractMap<String,Object>() {
            public boolean containsKey(Object key){return map.containsKey(key);}
            public Object get(Object key){return wrap(field((String)key,map.get(key),map.get("revisionId") instanceof String&&map.get("changes") instanceof List<?>));}
            public Set<Entry<String,Object>> entrySet(){return new AbstractSet<>() {
                public int size(){return map.size();}
                public Iterator<Entry<String,Object>> iterator(){var iterator=map.entrySet().iterator();return new Iterator<>(){public boolean hasNext(){return iterator.hasNext();}public Entry<String,Object> next(){var entry=iterator.next();String key=(String)entry.getKey();return new SimpleImmutableEntry<>(key,wrap(field(key,entry.getValue(),map.get("revisionId") instanceof String&&map.get("changes") instanceof List<?>)));}};}
            };}
        };
        if(value instanceof List<?> list)return new AbstractList<Object>() {
            public int size(){return list.size();}public Object get(int i){return wrap(list.get(i));}
            public Iterator<Object> iterator(){var iterator=list.iterator();return new Iterator<>(){public boolean hasNext(){return iterator.hasNext();}public Object next(){return wrap(iterator.next());}};}
        };
        return value;
    }
}
