package deposit.common;

import java.util.logging.Logger;

public class ExceptionHandler {

	private static final Logger logger = Logger.getLogger(ExceptionHandler.class.getName());

	private ExceptionHandler() {}

	// Method to log the error message and throw a runtime exception
	public static void throwException(String errorMsg) {
		logger.severe(errorMsg);
		throw new RuntimeException(errorMsg);
	}

	// Overloaded method to log the error message and exception, then throw a runtime exception
	public static void throwException(String errorMsg, Exception e) {
		logger.severe(errorMsg);
		throw new RuntimeException(errorMsg, e);
	}
}
