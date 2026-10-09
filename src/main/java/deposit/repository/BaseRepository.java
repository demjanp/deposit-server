package deposit.repository;

import deposit.common.ExceptionHandler;

import java.util.logging.Logger;

public abstract class BaseRepository {

	protected final Logger logger;

	protected BaseRepository() {
		// Initialize logger with the subclass's name
		this.logger = Logger.getLogger(this.getClass().getName());
	}

	// Method to log the error message and throw a runtime exception
	protected void throwException(String errorMsg) {
		ExceptionHandler.throwException(errorMsg);
	}

	// Overloaded method to log the error message and exception, then throw a runtime exception
	protected void throwException(String errorMsg, Exception e) {
		ExceptionHandler.throwException(errorMsg, e);
	}
}
