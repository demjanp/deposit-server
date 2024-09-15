package deposit;

import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;

import java.io.FileWriter;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.Map;

public class CypherScriptExporter {

    private final Driver driver;

    public CypherScriptExporter(Driver driver) {
        this.driver = driver;
    }

    public void exportData() {
        try (FileWriter writer = new FileWriter("export.cypher")) {
            exportNodes(writer);
            exportRelationships(writer);
            System.out.println("Cypher script export completed.");
        } catch (IOException e) {
            System.err.println("An error occurred while exporting the Cypher script: " + e.getMessage());
            e.printStackTrace();
        }
    }
	
    private void exportNodes(FileWriter writer) throws IOException {
        try (Session session = driver.session()) {
            Result result = session.run("MATCH (n) RETURN DISTINCT n");
            while (result.hasNext()) {
                Record record = result.next();
                Node node = record.get("n").asNode();

                // Build labels string
                StringBuilder labels = new StringBuilder();
                for (String label : node.labels()) {
                    labels.append(":").append(label);
                }

                // Build properties string
                String properties = mapToString(node.asMap());

                // Write CREATE statement
                String createStmt = String.format("CREATE (n%s %s);%n", labels.toString(), properties);
                writer.write(createStmt);
            }
        }
    }

    private void exportRelationships(FileWriter writer) throws IOException {
        try (Session session = driver.session()) {
            Result result = session.run("MATCH (a)-[r]->(b) RETURN DISTINCT r, a, b");
            while (result.hasNext()) {
                Record record = result.next();
                Relationship rel = record.get("r").asRelationship();
                Node startNode = record.get("a").asNode();
                Node endNode = record.get("b").asNode();

                // Check if 'id' property exists on both nodes
                if (!startNode.containsKey("id") || !endNode.containsKey("id")) {
                    System.err.println("One of the nodes does not have an 'id' property. Skipping relationship.");
                    continue;
                }

                // Get 'id' property of start and end nodes
                Object startIdObj = startNode.get("id").asObject();
                Object endIdObj = endNode.get("id").asObject();

                // Build relationship type and properties
                String type = rel.type();
                String properties = mapToString(rel.asMap());

                // Write MATCH and CREATE statements
                String matchStmt = String.format("MATCH (a), (b) WHERE a.id = %s AND b.id = %s%n",
                        formatValue(startIdObj), formatValue(endIdObj));
                String createStmt = String.format("CREATE (a)-[:%s %s]->(b);%n", type, properties);
                writer.write(matchStmt);
                writer.write(createStmt);
            }
        }
    }

    private String mapToString(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        map.forEach((key, value) -> {
            if (!key.equals("createdBy") && !key.equals("updatedBy")) {
                if (value instanceof String) {
                    sb.append(key).append(": ").append("\"").append(value.toString().replace("\"", "\\\"")).append("\", ");
                } else if (value instanceof ZonedDateTime) {
                    // pass
                } else {
                    sb.append(key).append(": ").append(value).append(", ");
                }
            }
        });
        if (!sb.isEmpty()) {
            sb.setLength(sb.length() - 2); // Remove trailing comma and space
            return "{" + sb.toString() + "}";
        }
        return "";
    }

    private String formatValue(Object value) {
        if (value instanceof String) {
            return "\"" + value.toString().replace("\"", "\\\"") + "\"";
        } else {
            return value.toString();
        }
    }
}
