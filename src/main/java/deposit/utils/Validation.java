package deposit.utils;

import java.util.Arrays;
import java.util.List;

public class Validation {

	private static final List<String> VALID_TYPES = Arrays.asList(
			"String", "Integer", "Float", "Boolean", "Date", "Time", "LocalTime", "DateTime", "LocalDateTime",
			"Duration", "Point", "List", "DDict", "DGeometry", "DResource"
	);

	public static void validateDescriptorTypeName(String typeName) {
		if (!VALID_TYPES.contains(typeName)) {
			throw new IllegalArgumentException("Invalid type: " + typeName + ". Must be one of: " + VALID_TYPES);
		}
	}

	/**
	 * Validates a position represented as a list of two doubles.
	 * If the position values are numeric but not doubles, they are converted to Double.
	 *
	 * @param position The position to validate.
	 * @return The validated position with Double values.
	 * @throws IllegalArgumentException if the position is invalid.
	 */
	public static List<Double> validatePosition(List<? extends Number> position) {
		// Validate position
		if (position == null || position.size() != 2) {
			throw new IllegalArgumentException("Position must be a list of exactly two elements.");
		}

		Double x, y;

		try {
			x = position.get(0).doubleValue();
			y = position.get(1).doubleValue();
		} catch (Exception e) {
			throw new IllegalArgumentException("Position elements must be numeric values.", e);
		}

		return List.of(x, y);
	}


	/**
	 * Validates a list of Class Descriptors.
	 *
	 * @param descriptors The descriptors to validate.
	 * @throws IllegalArgumentException if the descriptors are invalid.
	 */
	public static void validateClassDescriptors(List<List<String>> descriptors) {
		// Validate descriptors
		if (descriptors == null) {
			throw new IllegalArgumentException("Descriptors must be a list of lists.");
		}

		for (List<String> descriptor : descriptors) {
			if (descriptor.size() < 2 || descriptor.size() > 3) {
				throw new IllegalArgumentException("Descriptor must be a list of two or three elements.");
			}

			String type = descriptor.get(1);
			validateDescriptorTypeName(type);
		}
	}
}
