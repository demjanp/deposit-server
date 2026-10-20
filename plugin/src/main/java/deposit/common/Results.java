package deposit.common;

import java.util.*;
import org.neo4j.graphdb.*;

public final class Results {
    private Results() {}
    public static class QueryRowResult {
        public final Map<String,Object> data,presence,meta;
        public final List<Map<String,Object>> columns;
        public QueryRowResult(Map<String,Object> data,List<Map<String,Object>> columns,Map<String,Object> presence,Map<String,Object> meta){this.data=data;this.columns=columns;this.presence=presence;this.meta=meta;}
    }
    public static class EntityRow {
        public final Node node; public final Map<String,Object> data,meta;
        public EntityRow(Node node,Map<String,Object> data,Map<String,Object> meta) {this.node=node;this.data=data;this.meta=meta;}
    }
    public static class RelRow {
        public final Relationship rel; public final Map<String,Object> data,meta;
        public RelRow(Relationship rel,Map<String,Object> data,Map<String,Object> meta) {this.rel=rel;this.data=data;this.meta=meta;}
    }
    public static class DataRow {
        public final Map<String,Object> data,meta;
        public DataRow(Map<String,Object> data,Map<String,Object> meta) {this.data=data;this.meta=meta;}
    }
    public static class ValueRow {
        public final Object value; public final Map<String,Object> meta;
        public ValueRow(Object value,Map<String,Object> meta) {this.value=value;this.meta=meta;}
    }
    /** Passive internal eligibility projection used by the public result adapter. */
    public static class EligibilityRow {
        public final boolean value; public final Map<String,Object> data,meta;
        public EligibilityRow(boolean value,Map<String,Object> data,Map<String,Object> meta){this.value=value;this.data=data;this.meta=meta;}
    }
    public static class LoginRow {
        public final String value; public final Map<String,Object> data,meta;
        public LoginRow(String value,Map<String,Object> data,Map<String,Object> meta) {this.value=value;this.data=data;this.meta=meta;}
    }
    public record Outcome(Node node,Relationship rel,Map<String,Object> data) {
        public EntityRow entity(RequestContext c) {return new EntityRow(node,data,c.meta());}
        public DataRow structured(RequestContext c) {return new DataRow(data,c.meta());}
    }
}
