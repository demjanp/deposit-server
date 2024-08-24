package deposit.common;

import java.util.logging.Logger;

public class ExceptionHandler {

	private static final Logger logger = Logger.getLogger(ExceptionHandler.class.getName());

	// Thread-local flag to track when an exception is handled
	private static final ThreadLocal<Boolean> exceptionHandled = ThreadLocal.withInitial(() -> false);

	private ExceptionHandler() {}

	// Method to log the error message and throw a runtime exception
	public static void throwException(String errorMsg) {
		logger.severe(errorMsg);
		throw new RuntimeException(errorMsg);
	}

	// Overloaded method to log the error message and exception, then throw a runtime exception
	public static void throwException(String errorMsg, Exception e) {
		logger.severe(errorMsg);
		exceptionHandled.set(true);  // Mark that an exception was handled
		throw new RuntimeException(errorMsg, e);
	}

	// Method to check if an exception has been handled
	public static boolean hasExceptionBeenHandled() {
		return exceptionHandled.get();
	}

	// Reset the thread-local flag (useful after each operation)
	public static void resetExceptionHandledFlag() {
		exceptionHandled.remove();
	}
}
