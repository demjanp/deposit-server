package deposit.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class DResource {
	public String stored;
	public String filename;
	public Boolean isImage;
	
	public DResource(String stored, String filename, Boolean isImage) {
		this.stored = stored;
		this.filename = filename;
		this.isImage = isImage;
	}
	
	public DResource(String jsonData) {
		ObjectMapper mapper = new ObjectMapper();
		try {
			JsonNode jsonNode = mapper.readTree(jsonData);
			this.stored = jsonNode.get("stored").asText();
			this.filename = jsonNode.get("filename").asText();
			this.isImage = jsonNode.get("isImage").asBoolean();
		} catch (JsonProcessingException e) {
			ExceptionHandler.throwException("Error loading resource from the database: " + jsonData, e);
		}
	}
	
	public String toJSON() {
		ObjectMapper mapper = new ObjectMapper();
		try {
			ObjectNode jsonNode = mapper.createObjectNode();
			jsonNode.put("stored", this.stored);
			jsonNode.put("filename", this.filename);
			jsonNode.put("isImage", this.isImage);
			return mapper.writeValueAsString(jsonNode);
		} catch (JsonProcessingException e) {
			ExceptionHandler.throwException("Error converting the resource to JSON string format", e);
			return null;
		}
	}
}