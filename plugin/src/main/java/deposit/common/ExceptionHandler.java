package deposit.common;

import java.util.*;

public final class ExceptionHandler {
    private ExceptionHandler() {}
    public static RuntimeException error(String code, String message, RequestContext context, boolean retryable, Map<String,Object> details) {
        return new DepositException("DEPOSIT_ERROR "+Json.write(Json.map("code",code,"message",message,
            "requestId",context==null?null:context.requestId,"retryable",retryable,"details",details)));
    }
    public static RuntimeException error(String code, String message, RequestContext context, boolean retryable, Map<String,Object> details, Throwable cause) {
        // Never attach an unsafe cause to the public procedure exception.
        return error(code,message,context,retryable,details);
    }
    public static RuntimeException fail(String code,String message) {return error(code,message,null,false,Map.of());}
    public static final class DepositException extends RuntimeException {
        private DepositException(String message) {super(message);}
    }
}
