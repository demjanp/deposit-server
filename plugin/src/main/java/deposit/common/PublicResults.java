package deposit.common;

import java.util.*;
import org.neo4j.graphdb.*;

/** Public Cypher columns are deliberately independent of internal receipt outcomes. */
public final class PublicResults {
    private PublicResults() {}
    public static final class NodeRow { public final Node node; NodeRow(Node n){node=n;} }
    public static final class RelRow { public final Relationship rel; RelRow(Relationship r){rel=r;} }
    public static final class ValueRow { public final Object value; ValueRow(Object v){value=v;} }
    public static final class DataRow { public final Map<String,Object> data; public DataRow(Map<String,Object> d){data=d;} }
    public static final class QueryRow {
        public final Map<String,Object> data;
        public final List<Map<String,Object>> columns;
        public final Map<String,Object> presence;
        QueryRow(Results.QueryRowResult r){data=r.data;presence=r.presence;columns=r.columns;}
    }
    public static final class EligibilityRow {
        public final boolean value; public final Map<String,Object> data;
        EligibilityRow(Results.EligibilityRow r){value=r.value;data=r.data;}
    }
    public static NodeRow node(Object r){return new NodeRow(((Results.EntityRow)r).node);}
    public static RelRow rel(Object r){return new RelRow(((Results.RelRow)r).rel);}
    public static ValueRow value(Object r){return new ValueRow(r instanceof Results.LoginRow l?l.value:((Results.ValueRow)r).value);}
    public static DataRow data(Object r){return new DataRow(((Results.DataRow)r).data);}
    public static DataRow page(Object r){var row=(Results.DataRow)r;var data=new LinkedHashMap<>(row.data);data.put("nextCursor",row.meta.get("nextCursor"));return new DataRow(data);}
    public static QueryRow query(Object r){return new QueryRow((Results.QueryRowResult)r);}
    public static EligibilityRow eligibility(Object r){return new EligibilityRow((Results.EligibilityRow)r);}
}
