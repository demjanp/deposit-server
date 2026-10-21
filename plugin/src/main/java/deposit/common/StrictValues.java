package deposit.common;

/** Strict scalar conversions shared by service and repository boundaries. */
public final class StrictValues {
    private StrictValues() {}

    public static String string(Object value) {
        if(!(value instanceof String text))throw ExceptionHandler.fail("VALIDATION_FAILED","Expected String");
        return text;
    }

    public static long integer(Object value) {
        if(!(value instanceof Long||value instanceof Integer))throw ExceptionHandler.fail("VALIDATION_FAILED","Expected Integer");
        return ((Number)value).longValue();
    }
}
