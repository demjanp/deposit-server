package deposit.common.result;

import org.neo4j.graphdb.Node;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

public class QueryResult {
    public Map<String, Node> result;

    public QueryResult() {
        this.result = new LinkedHashMap<>();
    }

    public void addNode(String key, Node node) {
        result.put(key, node);
    }

    public Node get(String key) {
        return result.get(key);
    }

    public Map<String, Node> getAll() {
        return result;
    }
	
    public List<String> keys() {
        return new ArrayList<>(result.keySet());
    }
	
    @Override
    public String toString() {
        return result.entrySet().stream()
            .map(entry -> entry.getKey() + ": " + entry.getValue().toString())
            .collect(Collectors.joining(", ", "{", "}"));
    }
}
