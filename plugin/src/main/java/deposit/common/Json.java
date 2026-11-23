package deposit.common;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.BufferedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.DataOutputStream;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/** JSON used for protocol maps, never implicit conversion of scientific values. */
public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.USE_LONG_FOR_INTS);
    // Staged maps already supply stable iteration. Jackson's map sorting copies an
    // entire spill index into heap, so aggregate exchange serialization omits it.
    private static final ObjectMapper STREAM_MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.USE_LONG_FOR_INTS);
    static { STREAM_MAPPER.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxStringLength(Integer.MAX_VALUE).build()); }
    private Json() {}
    public static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("Cannot encode portable value", e); }
    }
    public static void write(OutputStream output,Object value) {
        try { STREAM_MAPPER.writeValue(output,value); }
        catch (Exception e) { throw new IllegalArgumentException("Cannot encode portable value", e); }
    }
    static void writeCanonical(OutputStream output,Object value) {
        try { MAPPER.writeValue(output,value); }
        catch (Exception e) { throw new IllegalArgumentException("Cannot encode portable value", e); }
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> read(String value) {
        try { return MAPPER.readValue(value, Map.class); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid encoded record", e); }
    }
    @SuppressWarnings("unchecked") public static List<Object> readList(String value) {
        try { return MAPPER.readValue(value, List.class); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid encoded list", e); }
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> read(InputStream input) {
        try{return STREAM_MAPPER.readValue(input,Map.class);}catch(Exception e){throw new IllegalArgumentException("Invalid encoded record",e);}
    }
    public static Map<String,Object> sourceRecord(String value) {
        try{return STREAM_MAPPER.readValue(value,Map.class);}catch(Exception e){throw new IllegalArgumentException("Invalid source record",e);}
    }
    /** Read object records from one named JSON array without materializing the array. */
    public static final class ArrayReader implements AutoCloseable {
        private final JsonParser parser;private boolean started,finished;
        public ArrayReader(Path path,String field)throws java.io.IOException{parser=STREAM_MAPPER.getFactory().createParser(Files.newInputStream(path));if(parser.nextToken()!=JsonToken.START_OBJECT)throw new java.io.IOException("Expected JSON object");boolean found=false;while(parser.nextToken()!=JsonToken.END_OBJECT){if(parser.currentToken()!=JsonToken.FIELD_NAME)throw new java.io.IOException("Invalid JSON object");String name=parser.currentName();JsonToken value=parser.nextToken();if(field.equals(name)){if(value!=JsonToken.START_ARRAY)throw new java.io.IOException("Expected JSON array");found=true;started=true;break;}parser.skipChildren();}if(!found)throw new java.io.IOException("Missing JSON array: "+field);}
        @SuppressWarnings("unchecked") public Map<String,Object> next()throws java.io.IOException{if(finished)return null;JsonToken token=parser.nextToken();if(token==JsonToken.END_ARRAY){finished=true;return null;}if(token==null)throw new java.io.IOException("Truncated JSON array");return STREAM_MAPPER.readValue(parser,Map.class);}
        @Override public void close()throws java.io.IOException{parser.close();}
    }
    public static long indexArray(Path jsonPath,String field,Path indexPath)throws java.io.IOException{
        Files.createDirectories(indexPath.getParent());long count=0;try(var parser=STREAM_MAPPER.getFactory().createParser(Files.newInputStream(jsonPath));var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(indexPath)))){
            if(parser.nextToken()!=JsonToken.START_OBJECT)throw new java.io.IOException("Expected JSON object");boolean found=false;while(parser.nextToken()!=JsonToken.END_OBJECT){if(parser.currentToken()!=JsonToken.FIELD_NAME)throw new java.io.IOException("Invalid JSON object");String name=parser.currentName();JsonToken value=parser.nextToken();if(!field.equals(name)){parser.skipChildren();continue;}if(value!=JsonToken.START_ARRAY)throw new java.io.IOException("Expected JSON array");found=true;JsonToken token;while((token=parser.nextToken())!=JsonToken.END_ARRAY){if(token!=JsonToken.START_OBJECT)throw new java.io.IOException("Expected array object");long offset=parser.getTokenLocation().getByteOffset();if(offset<0)throw new java.io.IOException("Missing JSON byte offset");out.writeLong(offset);count++;parser.skipChildren();}break;}if(!found)throw new java.io.IOException("Missing JSON array: "+field);
        }return count;
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> objectAt(Path jsonPath,long offset)throws java.io.IOException{try(InputStream raw=Files.newInputStream(jsonPath)){raw.skipNBytes(offset);try(JsonParser parser=STREAM_MAPPER.getFactory().createParser(raw)){if(parser.nextToken()!=JsonToken.START_OBJECT)throw new java.io.IOException("Invalid staged row index");return STREAM_MAPPER.readValue(parser,Map.class);}}}
    public static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch (NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    public static Map<String,Object> map(Object... entries) {
        var result=new LinkedHashMap<String,Object>();
        for(int i=0;i<entries.length;i+=2) result.put((String)entries[i],entries[i+1]);
        return result;
    }
}
