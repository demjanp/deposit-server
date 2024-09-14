package deposit.common.result;

import org.neo4j.graphdb.Node;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

public class QueryResult {

    public List<Map<String, Object>> result;

    public QueryResult() {
        this.result = new ArrayList<>();
    }

    public void addNode(String key, Node node) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(key, node);
        result.add(entry);
    }

    public List<Map<String, Object>> getAll() {
        return result;
    }

    @Override
    public String toString() {
        return result.stream()
            .map(map -> map.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue().toString())
                .collect(Collectors.joining(", ")))
            .collect(Collectors.joining(", ", "[", "]"));
    }
}
