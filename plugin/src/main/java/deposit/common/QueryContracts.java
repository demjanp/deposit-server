package deposit.common;

import java.util.*;
import org.neo4j.graphdb.Node;

/** Passive contracts shared by query planning, execution and graph access. */
public final class QueryContracts {
    private QueryContracts() {}

    public record ClassInfo(String id, Map<String,String> types) {}
    public record Field(String binding, String name, String type, String variable) {}
    public record Cell(String type, Object value, boolean present) {}

    /** Immutable repository input. Binding iteration order determines returned Object columns. */
    public record ExecutionPlan(String cypher, Map<String,Object> parameters,
                                Map<String,String> bindings, List<Field> fields) {
        public ExecutionPlan {
            parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
            bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
            fields = List.copyOf(fields);
        }
    }

    /** One graph row whose fields are decoded on demand by query execution. */
    public record Binding(Map<String,Node> objects, java.util.function.Function<Field,Cell> field,
                          Map<String,Object> raw, List<String> ids) {
        public Binding(Map<String,Node> objects,Map<Field,Cell> fields,Map<String,Object> raw,List<String> ids) {
            this(objects,fields::get,raw,ids);
        }
        public Cell cell(Field key) {return field.apply(key);}
    }
}
