package deposit.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ValidationUtilsTest {

	@Test
	void validatePosition_withValidPosition() {
		List<Double> expected = Arrays.asList(1.0, 2.0);
		List<Double> actual = ValidationUtils.validatePosition(Arrays.asList(1, 2));
		assertEquals(expected, actual);
	}

	@Test
	void validatePosition_withNullPosition() {
		assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validatePosition(null));
	}

	@Test
	void validatePosition_withMoreThanTwoElements() {
		assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validatePosition(Arrays.asList(1, 2, 3)));
	}

	@Test
	void validateDescriptors_withNullClassDescriptors() {
		assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateClassDescriptors(null));
	}

	@Test
	void validateDescriptors_withEmptyClassDescriptors() {
		assertDoesNotThrow(() -> ValidationUtils.validateClassDescriptors(new ArrayList<>()));
	}

	@Test
	void validateDescriptors_withValidClassDescriptors() {
		assertDoesNotThrow(() -> ValidationUtils.validateClassDescriptors(Arrays.asList(Arrays.asList("name1", "String"), Arrays.asList("name2", "Integer", "comment2"))));
	}

	@Test
	void validateClassDescriptors_withDescriptorSizeLessThanTwo() {
		assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateClassDescriptors(Arrays.asList(Arrays.asList("name1"))));
	}

	@Test
	void validateClassDescriptors_withInvalidType() {
		List<List<String>> descriptors = Arrays.asList(Arrays.asList("name1", "InvalidType"));
		assertThrows(IllegalArgumentException.class, () -> ValidationUtils.validateClassDescriptors(descriptors));
	}
}