package deposit.service;

import java.util.logging.Logger;

public abstract class BaseService {

	protected final Logger logger;

	protected BaseService() {
		// Initialize logger with the subclass's name
		this.logger = Logger.getLogger(this.getClass().getName());
	}

	// Method to log the error message and throw a runtime exception
	protected void throwException(String errorMsg) {
		logger.severe(errorMsg);
		throw new RuntimeException(errorMsg);
	}

	// Overloaded method to log the error message and exception, then throw a runtime exception
	protected void throwException(String errorMsg, Exception e) {
		logger.severe(errorMsg);
		throw new RuntimeException(errorMsg, e);
	}
}
