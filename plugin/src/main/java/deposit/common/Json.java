package deposit.common;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;

/** JSON used for protocol maps, never implicit conversion of scientific values. */
public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.USE_LONG_FOR_INTS);
    private Json() {}
    public static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("Cannot encode portable value", e); }
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> read(String value) {
        try { return MAPPER.readValue(value, Map.class); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid encoded record", e); }
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
