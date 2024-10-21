package deposit.common;

import java.util.*;

public class PatternData {
	
	/**
	 * Parses data representing a Pattern object into an instance of that object.
	 *
	 * @param data Map<String, Object> {
	 *		id, name, comment, 
	 *		objects: {
	 *			id: {
	 *				id, name, className, root, unique, matchEmpty, comment,
	 *				descriptors: [{name, type, comment}, ...],
	 *				relationships: [{label, targetId, comment}, ...]
	 *			}
	 *		}
	 *	}
	 *
	 **/
	public static Pattern parseData(Map<String, Object> data) {
		try {
			Pattern pattern = new Pattern();
			pattern.id = (String) data.getOrDefault("id", null);
			pattern.name = (String) ensureValue(data, "name");
			pattern.comment = (String) data.getOrDefault("comment", "");
			pattern.objects = new HashMap<>();
			Map<String, Map<String, Object>> objects = (Map<String, Map<String, Object>>) data.getOrDefault("objects", Map.of());
			for (Map<String, Object> objectData : objects.values()) {
				PObject pObject = new PObject();
				pObject.id = (String) ensureValue(objectData, "id");
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
					pRelationship.targetId = (String) ensureValue(relData, "targetId");
					pRelationship.comment = relData.getOrDefault("comment", "");
					pObject.relationships.add(pRelationship);
				}
				pattern.objects.put(pObject.id, pObject);
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
		public String id;
		public String name;
		public String comment;
		public Map<String, PObject> objects;
		
		@Override
		public String toString() {
			return this.toString();
		}
	}
	
	public static class PObject {
		public String id;
		public String name;
		public String className;
		public Boolean root;
		public Boolean unique;
		public Boolean matchEmpty;
		public String comment;
		public List<PDescriptor> descriptors;
		public List<PRelationship> relationships;
		
		@Override
		public String toString() {
			return this.toString();
		}
	}
	
	public static class PDescriptor {
		public String name;
		public String type;
		public String comment;
		
		@Override
		public String toString() {
			return this.toString();
		}
	}
	
	public static class PRelationship {
		public String label;
		public String targetId;
		public String comment;
		
		@Override
		public String toString() {
			return this.toString();
		}
	}
}