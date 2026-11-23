package deposit.common;

import java.util.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class CursorCodec {
    private CursorCodec() {}
    private static byte[] sign(byte[] key,String payload) {
        try {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));}
        catch(Exception e) {throw new IllegalStateException(e);}
    }
    /** Keyed canonical identity prevents account receipts becoming a fast password oracle. */
    public static String requestHash(RequestContext c,String canonicalRequest) {
        return "hmac-sha256:"+Base64.getUrlEncoder().withoutPadding().encodeToString(sign(c.server.cursorKey,"receipt:"+canonicalRequest));
    }
    public static String requestHash(RequestContext c,Map<String,Object> request) {
        final Mac mac;
        try {
            mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(c.server.cursorKey,"HmacSHA256"));
        } catch(java.security.GeneralSecurityException e){throw new IllegalStateException(e);}
        mac.update("receipt:".getBytes(StandardCharsets.UTF_8));
        Json.writeCanonical(new OutputStream(){
            public void write(int value){mac.update((byte)value);}
            public void write(byte[] values,int offset,int length){mac.update(values,offset,length);}
        },request);
        return "hmac-sha256:"+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
    }
    public static String encode(RequestContext c,Map<String,Object> payload) {
        String body=Base64.getUrlEncoder().withoutPadding().encodeToString(Json.write(payload).getBytes(StandardCharsets.UTF_8));
        return body+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(sign(c.server.cursorKey,body));
    }
    public static Map<String,Object> decode(RequestContext c,String cursor) {
        try {
            String[] parts=cursor.split("\\.");if(parts.length!=2||!MessageDigest.isEqual(sign(c.server.cursorKey,parts[0]),Base64.getUrlDecoder().decode(parts[1])))throw new IllegalArgumentException();
            var payload=Json.read(new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8));
            if(((Number)payload.get("expires")).longValue()<System.currentTimeMillis())throw new IllegalArgumentException();return payload;
        } catch(RuntimeException e) {throw ExceptionHandler.error("VALIDATION_FAILED","Invalid or expired cursor",c,false,Map.of());}
    }
}
