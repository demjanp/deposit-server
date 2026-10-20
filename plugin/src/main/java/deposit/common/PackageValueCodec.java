package deposit.common;

import java.io.*;
import java.util.*;

/** Declaration-directed portable scientific values and metadata counters. */
final class PackageValueCodec {
    private PackageValueCodec() {}
    static Object encodeValue(String type,Object value){
        if(value==null)return null;
        return switch(type){
            case "Integer"->Long.toString(((Number)value).longValue());
            case "List"->{var m=ValueCodec.map(value);String item=(String)m.get("itemType");var items=new ArrayList<>();for(Object itemValue:(List<?>)m.get("items"))items.add(encodeValue(item,itemValue));yield Json.map("itemType",item,"items",items);}
            case "DDict"->{var out=new TreeMap<String,Object>();ValueCodec.map(value).forEach((key,raw)->{var row=ValueCodec.map(raw);out.put(key,Json.map("type",row.get("type"),"value",encodeValue((String)row.get("type"),row.get("value"))));});yield out;}
            default->value instanceof Map<?,?>?new TreeMap<>(ValueCodec.map(value)):value;
        };
    }
    /** Decode the package's declaration-directed wire forms for repository writes. */
    static Object decodeValue(String type,Object value){
        if(value==null)return null;
        return switch(type){
            case "Integer"->Long.parseLong((String)value);
            case "List"->{var m=ValueCodec.map(value);String item=(String)m.get("itemType");var values=new ArrayList<>();for(Object raw:(List<?>)m.get("items"))values.add(decodeValue(item,raw));yield Json.map("itemType",item,"items",values);}
            case "DDict"->{var out=new TreeMap<String,Object>();ValueCodec.map(value).forEach((key,raw)->{var row=ValueCodec.map(raw);String item=(String)row.get("type");out.put(key,Json.map("type",item,"value",decodeValue(item,row.get("value"))));});yield out;}
            default->value;
        };
    }
    static Object validateValue(String type,Object value)throws IOException{return validateValue(type,value,false,0);}
    static Object validateValue(String type,Object value,boolean fingerprints,int depth)throws IOException {
        if(depth>0&&Set.of("DGeometry","DResource").contains(type))throw new IOException("Geometry/resources cannot occur in collections");
        if(value==null)return null;
        try{return switch(type){
            case "String"->{if(!(value instanceof String||value instanceof PackageWorkspace.Text))throw new IllegalArgumentException();yield fingerprints?PackageWorkspace.fingerprint(value):PackageWorkspace.materializeText(value);}
            case "Integer"->{if(value instanceof PackageWorkspace.Text large&&large.length()>20)throw new IllegalArgumentException();Object text=PackageWorkspace.materializeText(value);if(!(text instanceof String s)||s.length()>20)throw new IllegalArgumentException();long result=Long.parseLong(s);if(!Long.toString(result).equals(s))throw new IllegalArgumentException();yield result;}
            case "List"->{var m=ValueCodec.map(value);if(!m.keySet().equals(Set.of("itemType","items"))||!(m.get("items") instanceof List<?> list))throw new IllegalArgumentException();String item=ValueCodec.type(m.get("itemType"));if(Set.of("DGeometry","DResource").contains(item))throw new IllegalArgumentException();
                for(Object x:list)validateValue(item,x,fingerprints,depth+1);
                var items=new AbstractList<Object>(){
                    public boolean equals(Object other){if(other==this)return true;if(!(other instanceof List<?> right)||right.size()!=size())return false;var left=iterator();var it=right.iterator();while(left.hasNext())if(!Objects.equals(left.next(),it.next()))return false;return true;}
                    public int size(){return list.size();}public Object get(int i){try{return validateValue(item,list.get(i),fingerprints,depth+1);}catch(IOException e){throw new UncheckedIOException(e);}}
                    public Iterator<Object> iterator(){var iterator=list.iterator();return new Iterator<>(){public boolean hasNext(){return iterator.hasNext();}public Object next(){try{return validateValue(item,iterator.next(),fingerprints,depth+1);}catch(IOException e){throw new UncheckedIOException(e);}}};}
                };yield Json.map("itemType",item,"items",items);}
            case "DDict"->{var map=fingerprints?PackageWorkspace.dictionary(ValueCodec.map(value)):ValueCodec.map(value);for(var entry:map.entrySet()){var row=ValueCodec.map(entry.getValue());if(!row.keySet().equals(Set.of("type","value")))throw new IllegalArgumentException();validateValue(ValueCodec.type(row.get("type")),row.get("value"),fingerprints,depth+1);}
                yield new AbstractMap<String,Object>(){public Set<Entry<String,Object>> entrySet(){return new AbstractSet<>(){public int size(){return map.size();}public Iterator<Entry<String,Object>> iterator(){var iterator=map.entrySet().iterator();return new Iterator<>(){public boolean hasNext(){return iterator.hasNext();}public Entry<String,Object> next(){var entry=iterator.next();var row=ValueCodec.map(entry.getValue());String t=ValueCodec.type(row.get("type"));try{return new SimpleImmutableEntry<>(entry.getKey(),Json.map("type",t,"value",validateValue(t,row.get("value"),fingerprints,depth+1)));}catch(IOException e){throw new UncheckedIOException(e);}}};}};}};}
            case "DGeometry"->{var map=new LinkedHashMap<>(ValueCodec.map(value));map.put("wkt",PackageWorkspace.materializeText(map.get("wkt")));yield ValueCodec.normalize(type,map);}
            default->ValueCodec.normalize(type,PackageWorkspace.materializeText(value));
        };}catch(RuntimeException e){throw new IOException("Invalid typed scientific value",e);}
    }
    static void metadataInteger(Object value)throws IOException{if(!(value instanceof Long||value instanceof Integer)||((Number)value).longValue()<0||((Number)value).longValue()>9_007_199_254_740_991L)throw new IOException("Metadata counter is not a portable integer");}
}
