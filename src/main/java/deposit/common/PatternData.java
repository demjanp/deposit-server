package deposit.common;

import java.util.*;
import java.util.stream.Collectors;

public class PatternData {
	
	/**
	 * Parses data representing a Pattern object into an instance of that object.
	 *
	 * @param data Map<String, Object> {
	 *		id, name, comment, 
	 *		objects: {
	 *			name: {
	 *				name, className, root, unique, matchEmpty, comment,
	 *				descriptors: [{name, type, comment}, ...],
	 *				relationships: [{label, targetName, comment}, ...]
	 *			}
	 *		}
	 *	}
	 *
	 **/
	public static Pattern parseData(Map<String, Object> data) {
		try {
			Pattern pattern = new Pattern();
			pattern.name = (String) ensureValue(data, "name");
			pattern.comment = (String) data.getOrDefault("comment", "");
			pattern.objects = new HashMap<>();
			Map<String, Map<String, Object>> objects = (Map<String, Map<String, Object>>) data.getOrDefault("objects", Map.of());
			for (Map<String, Object> objectData : objects.values()) {
				PObject pObject = new PObject();
				pObject.name = (String) ensureValue(objectData, "name");
				pObject.className = (String) ensureValue(objectData, "className");
				pObject.root = (Boolean) objectData.getOrDefault("root", false);
				pObject.unique = (Boolean) objectData.getOrDefault("unique", false);
				pObject.matchEmpty = (Boolean) objectData.getOrDefault("matchEmpty", false);
				pObject.comment = (String) objectData.getOrDefault("comment", "");
				pObject.descriptors = new ArrayList<>();
				for (Map<String, String> descrData : (List<Map<String, String>>) objectData.getOrDefault("descriptors", List.of())) {
					PDescriptor pDescriptor = new PDescriptor();
					pDescriptor.name = (String) ensureValue(descrData, "name");
					pDescriptor.type = descrData.getOrDefault("type", "String");
					pDescriptor.comment = descrData.getOrDefault("comment", "");
					pObject.descriptors.add(pDescriptor);
				}
				pObject.relationships = new ArrayList<>();
				for (Map<String, String> relData : (List<Map<String, String>>) objectData.getOrDefault("relationships", List.of())) {
					PRelationship pRelationship = new PRelationship();
					pRelationship.label = (String) ensureValue(relData, "label");
					pRelationship.targetName = (String) ensureValue(relData, "targetName");
					pRelationship.comment = relData.getOrDefault("comment", "");
					pObject.relationships.add(pRelationship);
				}
				pattern.objects.put(pObject.name, pObject);
			}
			return pattern;
		} catch (Exception e) {
			ExceptionHandler.throwException("Error parsing Pattern data", e);
			return null;
		}
	}
	
	private static Object ensureValue(Map<String, ?> data, String key) {
		if (!data.containsKey(key)) {
			ExceptionHandler.throwException("Error parsing Pattern data, key missing: " + key);
		}
		return data.get(key);
	}
	
	public static class Pattern {
		public String name;
		public String comment = "";
		public Map<String, PObject> objects;
		
		@Override
		public String toString() {
			return this.toString();
		}
		
		public Map<String, Object> toMap() {
			Map<String, Object> result = new HashMap<>();
			result.put("name", this.name);
			result.put("comment", this.comment);

			Map<String, Map<String, Object>> objectMap = this.objects.entrySet().stream()
				.collect(Collectors.toMap(
					Map.Entry::getKey,
					entry -> entry.getValue().toMap()
				));
			
			result.put("objects", objectMap);
			return result;
		}
	}
	
	public static class PObject {
		public String name;
		public String className;
		public Boolean root = false;
		public Boolean unique = false;
		public Boolean matchEmpty = false;
		public String comment = "";
		public List<PDescriptor> descriptors;
		public List<PRelationship> relationships;
		
		@Override
		public String toString() {
			return this.toString();
		}
		
		public Map<String, Object> toMap() {
			Map<String, Object> result = new HashMap<>();
			result.put("name", this.name);
			result.put("className", this.className);
			result.put("root", this.root);
			result.put("unique", this.unique);
			result.put("matchEmpty", this.matchEmpty);
			result.put("comment", this.comment);

			List<Map<String, Object>> descriptorList = this.descriptors.stream()
				.map(PDescriptor::toMap)
				.collect(Collectors.toList());
			result.put("descriptors", descriptorList);

			List<Map<String, Object>> relationshipList = this.relationships.stream()
				.map(PRelationship::toMap)
				.collect(Collectors.toList());
			result.put("relationships", relationshipList);
			
			return result;
		}
	}
	
	public static class PDescriptor {
		public String name;
		public String type = "String";
		public String comment = "";
		
		@Override
		public String toString() {
			return this.toString();
		}
		
		public Map<String, Object> toMap() {
			Map<String, Object> result = new HashMap<>();
			result.put("name", this.name);
			result.put("type", this.type);
			result.put("comment", this.comment);
			return result;
		}
	}
	
	public static class PRelationship {
		public String label;
		public String targetName;
		public String comment = "";
		
		@Override
		public String toString() {
			return this.toString();
		}
		
		public Map<String, Object> toMap() {
			Map<String, Object> result = new HashMap<>();
			result.put("label", this.label);
			result.put("targetName", this.targetName);
			result.put("comment", this.comment);
			return result;
		}
	}
}