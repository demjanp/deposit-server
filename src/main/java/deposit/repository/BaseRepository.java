package deposit.repository;

import java.util.logging.Logger;

public abstract class BaseRepository<T> {

	private static BaseRepository<?> instance;
	protected final Logger logger;

	protected BaseRepository() {
		// Initialize logger with the subclass's name
		this.logger = Logger.getLogger(this.getClass().getName());
	}

	// Synchronized method to get the instance of the repository
	public static synchronized <T> BaseRepository<T> getInstance(Class<T> clazz) {
		if (instance == null) {
			try {
				instance = (BaseRepository<?>) clazz.getDeclaredConstructor().newInstance();
			} catch (Exception e) {
				throw new RuntimeException("Failed to create instance of " + clazz.getName(), e);
			}
		}
		return (BaseRepository<T>) instance;
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
