package deposit.common;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.io.OutputStream;
import java.io.InputStream;

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
    @SuppressWarnings("unchecked") public static Map<String,Object> read(String value) {
        try { return MAPPER.readValue(value, Map.class); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid encoded record", e); }
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> read(InputStream input) {
        try{return STREAM_MAPPER.readValue(input,Map.class);}catch(Exception e){throw new IllegalArgumentException("Invalid encoded record",e);}
    }
    public static Map<String,Object> sourceRecord(String value) {
        try{return STREAM_MAPPER.readValue(value,Map.class);}catch(Exception e){throw new IllegalArgumentException("Invalid source record",e);}
    }
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
