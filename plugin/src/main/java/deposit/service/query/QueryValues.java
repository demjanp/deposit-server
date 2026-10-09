package deposit.service.query;

import deposit.common.*;
import deposit.repository.CoreRepository;
import java.util.*;
import org.neo4j.graphdb.Node;
import org.neo4j.values.storable.Values;

/** Equality and total ordering are separate: structured values have no public order. */
public final class QueryValues {
    private QueryValues(){}
    public record Cell(String type,Object value,boolean present) {}
    public static Object identity(Object value){if(value instanceof Node n)return CoreRepository.id(n);if(value instanceof Map<?,?> m){var out=new TreeMap<String,Object>();m.forEach((k,v)->out.put((String)k,identity(v)));return out;}if(value instanceof List<?> l)return l.stream().map(QueryValues::identity).toList();if(value instanceof Double d&&d==0)return 0.0;return value;}
    public static String key(Cell cell){return Json.write(Json.map("type",cell.type,"value",identity(cell.value),"presence",cell.present));}
    public static String key(List<Cell> cells){return Json.write(cells.stream().map(QueryValues::key).toList());}
    public static boolean equal(String type,Object a,Object b){return Objects.equals(identity(a),identity(b));}
    @SuppressWarnings({"rawtypes","unchecked"}) public static int compare(String type,Object a,Object b){
        if(a==null||b==null)return a==b?0:a==null?1:-1;
        if(type.equals("Object"))return CoreRepository.id((Node)a).compareTo(CoreRepository.id((Node)b));
        if(Set.of("String","Integer","Float","Boolean").contains(type))return ((Comparable)a).compareTo(b);
        if(!QueryTranslator.comparable(type))throw QueryTranslator.typeError("Value is not orderable");
        return Values.COMPARATOR.compare(Values.of(ValueCodec.storage(type,a)),Values.of(ValueCodec.storage(type,b)));
    }
}
