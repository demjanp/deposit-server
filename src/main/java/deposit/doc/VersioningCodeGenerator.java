package deposit.doc;

import deposit.common.Versioning;
import deposit.common.ServerContext;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class VersioningCodeGenerator {

	public static void main(String[] args) throws IOException {
		List<String> elementTypes = Arrays.asList(
			"Object", "Class", "Descriptor", "ClassDescriptor",
			"Membership", "Relationship", "ClassRelationship"
		);
		
		ServerContext.setVersioningType("permanent");
		Boolean deletion = false;

		for (String elementType : elementTypes) {
			String versioningCode = Versioning.getElementUpdateCode(elementType, deletion);
			saveToFile(elementType, versioningCode);
		}
	}

	private static void saveToFile(String elementType, String content) throws IOException {
		String fileName = elementType + ".cypher";
		try (FileWriter writer = new FileWriter(fileName)) {
			writer.write(content);
			System.out.println("Versioning code for elementType \"" + elementType + "\" saved to " + fileName);
		}
	}
}
