package deposit.api;

import deposit.common.ExceptionHandler;

import java.util.logging.Logger;

public abstract class BaseAPI {

    protected static final Logger logger = Logger.getLogger(BaseAPI.class.getName());

    protected BaseAPI() {
        // No need to initialize the logger here since it is static
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
