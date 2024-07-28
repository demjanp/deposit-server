package deposit.service;

import deposit.common.ExceptionHandler;
import deposit.common.ServerContext;

import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Arrays;
import java.util.List;

public abstract class BaseService {
	
	private static final List<String> VALID_TYPES = Arrays.asList(
			"String", "Integer", "Float", "Boolean", "Date", "Time", "LocalTime", "DateTime", "LocalDateTime",
			"Duration", "Point", "List", "DDict", "DGeometry", "DResource"
	);
	private static final String NAME_PATTERN = "^[\\p{L}\\p{N}\\-_.~ ]+$";
	private static final Pattern pattern = Pattern.compile(NAME_PATTERN);
	
	protected final Logger logger;
	
	protected BaseService() {
		// Initialize logger with the subclass's name
		this.logger = Logger.getLogger(this.getClass().getName());
	}

	// Method to log the error message and throw a runtime exception
	protected static void throwException(String errorMsg) {
		 ExceptionHandler.throwException(errorMsg);
	}

	// Overloaded method to log the error message and exception, then throw a runtime exception
	protected void throwException(String errorMsg, Exception e) {
		ExceptionHandler.throwException(errorMsg, e);
	}
	
	
	protected void validateToken(String token) {
		if (!ServerContext.validateToken(token)) {
			throwException("Invalid token");
		}
	}
	
	protected void validateName(String name) {
		Matcher matcher = pattern.matcher(name);
		if (!matcher.matches()) {
			throwException("Invalid name: " + name + 
				". Allowed characters are Unicode letters, digits, spaces, hyphens, underscores, dots, and tildes.");
		}
	}
	
	protected void validateDescriptorTypeName(String typeName) {
		if (!VALID_TYPES.contains(typeName)) {
			throwException("Invalid type: " + typeName + ". Must be one of: " + VALID_TYPES);
		}
	}
	
	protected List<Double> validatePosition(List<? extends Number> position) {
		if (position.size() == 0) {
			return List.of();
		}

		if (position.size() != 2) {
			throwException("Position must be a list of exactly two elements or empty.");
		}

		Double x = 0., y = 0.;

		try {
			x = position.get(0).doubleValue();
			y = position.get(1).doubleValue();
		} catch (Exception e) {
			throwException("Position elements must be numeric values.", e);
		}

		return List.of(x, y);
	}
	
	protected void validateClassDescriptors(List<List<String>> descriptors) {
		// Validate descriptors
		if (descriptors == null) {
			throwException("Descriptors must be a list of lists.");
		}

		for (List<String> descriptor : descriptors) {
			if (descriptor.size() < 2 || descriptor.size() > 3) {
				throwException("Descriptor must be a list of two or three elements.");
			}

			String type = descriptor.get(1);
			validateDescriptorTypeName(type);
		}
	}
	
}
