package deposit.service;

import deposit.common.ExceptionHandler;
import deposit.common.ServerContext;
import deposit.common.PatternData;

import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.time.*;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.values.storable.PointValue;
import org.neo4j.values.storable.DurationValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

public abstract class BaseService {
	
	private static final List<String> VALID_TYPES = Arrays.asList(
			"String", "Integer", "Float", "Boolean", "Date", "Time", "LocalDate", "LocalTime", "DateTime", "LocalDateTime",
			"ZonedDateTime", "Duration", "Point", "List", "DResource"
	);
	private static final String NAME_PATTERN = "^[\\p{L}\\p{N}\\-_.~ ]+$";
	private static final Pattern pattern = Pattern.compile(NAME_PATTERN);
	
	// Reserved names in Windows that cannot be used as filenames
	private static final String[] WINDOWS_RESERVED_NAMES = {
		"CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", 
		"LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
	};
	
	// Regular expression to match invalid filename characters across Windows, macOS, and Linux
	private static final Pattern INVALID_CHARACTERS = Pattern.compile("[<>:\"/\\|?*\0]");
	
	// Maximum filename length (for most filesystems, including NTFS and ext4)
	private static final int MAX_FILENAME_LENGTH = 255;
	
	private static ObjectMapper objectMapper;
	
	protected final Logger logger;
	
	protected BaseService() {
		// Initialize logger with the subclass's name
		this.logger = Logger.getLogger(this.getClass().getName());
		this.objectMapper = new ObjectMapper();
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
		//if (name.isEmpty()) {
		//	return;
		//}
		Matcher matcher = pattern.matcher(name);
		if (!matcher.matches() || name == null) {
			throwException("Invalid name: " + name + 
				". Allowed characters are Unicode letters, digits, spaces, hyphens, underscores, dots, and tildes.");
		}
	}
	
	protected void validateId(String uuid) {
		try {
			UUID.fromString(uuid);
		} catch (IllegalArgumentException e) {
			throwException("Invalid ID: " + uuid + ". The ID must be in the format xxxxxxxx-xxxx-Mxxx-Nxxx-xxxxxxxxxxxx.");
		}
	}
	
	protected void validateIds(List<String> uuids) {
		for (String uuid : uuids) {
			validateId(uuid);
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
		if (descriptors == null) {
			throwException("Descriptors must be a list of lists.");
		}

		for (List<String> descriptor : descriptors) {
			if (descriptor.size() < 2 || descriptor.size() > 3) {
				throwException("Descriptor must be a list of two or three elements.");
			}
			
			String name = descriptor.get(0);
			validateName(name);
			
			String type = descriptor.get(1);
			validateDescriptorTypeName(type);
		}
	}
	
	protected void validateDescriptorTypeName(String typeName) {
		if (!VALID_TYPES.contains(typeName)) {
			throwException("Invalid type: " + typeName + ". Must be one of: " + VALID_TYPES);
		}
	}
	
	private static Boolean isDResource(Object value) {
		if (value instanceof Node) {
			Node node = (Node) value;
			if (node.getLabels().iterator().hasNext()) {
				Label firstLabel = node.getLabels().iterator().next();
				if ("DResource".equals(firstLabel.name())) {
					return true;
				}
			}
		}
		return false;
	}
	
	protected void validateValue(Object value) {
		if (value instanceof String ||
			value instanceof Integer ||
			value instanceof Long ||
			value instanceof Float ||
			value instanceof Double ||
			value instanceof Boolean ||
			value instanceof java.time.LocalTime ||
			value instanceof java.time.LocalDate ||
			value instanceof java.time.LocalDateTime ||
			value instanceof java.time.ZonedDateTime ||
			value instanceof DurationValue ||
			value instanceof PointValue ||
			isDResource(value)) {
			// Valid single values that Neo4j supports directly
		} else if (value instanceof List<?>) {
			// Validate that all elements in the list are of the same type and are valid Neo4j types
			List<?> list = (List<?>) value;
			if (!list.isEmpty()) {
				Class<?> elementType = null;
				for (Object item : list) {
					if (elementType == null) {
						elementType = item.getClass();
					} else if (!elementType.equals(item.getClass())) {
						throwException("Invalid list: all elements must be of the same type.");
					}
					validateValue(item);  // Recursive validation of each list item
				}
			}
		} else {
			throwException("Invalid value type: " + value.getClass().getName() +
				". The value must be one of the supported Neo4j types.");
		}
	}
	
	public static String getTypeName(Object value) {
		if (value == null) {
			throwException("Value cannot be null");
		}
		
		String simpleName = value.getClass().getSimpleName();
		
		if (value instanceof Integer || value instanceof Long) {
			return "Integer";
		} else if (value instanceof Float || value instanceof Double) {
			return "Float";
		} else if (value instanceof List<?>) {
			return "List";
		} else if (isDResource(value)) {
			return "DResource";
		} else if (value instanceof DurationValue) {
			return "Duration";
		} else if (value instanceof PointValue) {
			return "Point";
		}
		
		if (VALID_TYPES.contains(simpleName)) {
			return simpleName;
		} else {
			throwException("Unsupported value type: " + value.getClass().getName());
		}
		return null;
	}

	protected void validateDescriptors(List<List<Object>> descriptors) {
		if (descriptors == null) {
			throwException("Descriptors must be a list of lists.");
		}

		for (List<Object> descriptor : descriptors) {
			if (descriptor.size() != 2) {
				throwException("Descriptor must be a list of two elements.");
			}
			
			String name = (String) descriptor.get(0);
			validateName(name);
			
			Object value = descriptor.get(1);
			validateValue(value);
		}
	}
	
	protected void validateJSONString(String data) {
		try {
			objectMapper.readTree(data);
		} catch (JsonProcessingException e) {
			throwException("Invalid JSON data: " + data + ". The data must be a valid JSON encoded dictionary.");
		}
	}
	
	protected void validateFilename(String filename) {
		
		if (filename == null || filename.isEmpty()) {
			throwException("Filename must nod be an empty string.");
		}

		if (INVALID_CHARACTERS.matcher(filename).find()) {
			throwException("Filename contains invalid characters: " + filename);
		}

		for (String reserved : WINDOWS_RESERVED_NAMES) {
			if (filename.equalsIgnoreCase(reserved)) {
				throwException("Filename is a reserved name under Windows: " + filename);
			}
		}

		if (filename.endsWith(".") || filename.endsWith(" ")) {
			throwException("Filename must not end with a period or space: " + filename);
		}

		if (filename.length() > MAX_FILENAME_LENGTH) {
			throwException("Filename exceeds the maximum length of " + MAX_FILENAME_LENGTH + " characters: " + filename);
		}
	}
	
	protected void validatePattern(PatternData.Pattern pattern) {
		if (pattern == null) {
			throwException("The provided Pattern is null");
		}
		validateName(pattern.name);
		Map<String, PatternData.PObject> pObjects = pattern.objects;
		Set<String> objectNames = new HashSet<>();
		int rootCount = 0;
		
		for (PatternData.PObject pObject : pObjects.values()) {
			if (objectNames.contains(pObject.name)) {
				throwException("Duplicate Pattern Object name: " + pObject.name);
			}
			objectNames.add(pObject.name);
			validateName(pObject.name);
			validateName(pObject.className);
			
			if (Boolean.TRUE.equals(pObject.root)) {
				rootCount++;
			}
			
			List<PatternData.PDescriptor> descriptors = pObject.descriptors;
			Set<String> desciptorNames = new HashSet<>();
			for (PatternData.PDescriptor descriptor : descriptors) {
				if (desciptorNames.contains(descriptor.name)) {
					throwException("Duplicate Pattern Descriptor name: " + descriptor.name);
				}
				desciptorNames.add(descriptor.name);
				validateName(descriptor.name);
				validateDescriptorTypeName(descriptor.type);
			}
			List<PatternData.PRelationship> relationships = pObject.relationships;
			Set<String> relHashes = new HashSet<>();
			for (PatternData.PRelationship relationship : relationships) {
				String relHash = relationship.label + "@" + relationship.targetName;
				if (relHashes.contains(relHash)) {
					throwException("Duplicate Pattern Relationship: " + relationship);
				}
				relHashes.add(relHash);
				validateName(relationship.label);
				if (!pObjects.containsKey(relationship.targetName)) {
					throwException("Pattern Relationship links to non-existing Pattern Object: " + relationship);
				}
			}
		}
		
		if (rootCount != 1) {
			throwException("Pattern must have exactly one root object. Found: " + rootCount);
		}
	}
}
