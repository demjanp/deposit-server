package deposit.service.query;

import deposit.common.*;
import java.util.*;
import java.util.function.Function;
import static deposit.service.query.QueryParser.*;

/** Typed logical plan and parameterized relational core; no database access. */
public final class QueryTranslator {
    public record ClassInfo(String id,Map<String,String> types) {}
    public record Field(String binding,String name,String type,String variable) {}
    public record Projection(Select item,Field field,String binding,String name,String type) {}
    public static final class QueryPlan {
        public final ParsedQuery parsed;public String cypher;
        public final Map<String,Object> parameters=new LinkedHashMap<>();
        public final LinkedHashMap<String,String> bindings=new LinkedHashMap<>();
        public final List<Field> fields=new ArrayList<>();public final List<Projection> projections=new ArrayList<>();
        public final Map<Predicate,Field> predicates=new IdentityHashMap<>();public final Map<Predicate,Object> values=new IdentityHashMap<>();
        public final Map<Relation,String> relations=new IdentityHashMap<>();public final List<Integer> order=new ArrayList<>();
        public boolean aggregate,collect;public List<Map<String,Object>> columns(){return projections.stream().map(p->Json.map("name",p.name,"type",p.type)).toList();}
        QueryPlan(ParsedQuery p){parsed=p;}
    }
    private final QueryPlan plan;private final Function<String,ClassInfo> schema;private final Runnable check;
    private final Map<String,ClassInfo> classes=new HashMap<>();private final Map<String,String> aliases=new LinkedHashMap<>();private final Map<String,Ref> scalars=new HashMap<>();
    private final Map<String,Object> input;private final Map<String,String> parameterTypes=new HashMap<>();private final Set<String> used=new HashSet<>();private int count;
    private QueryTranslator(ParsedQuery parsed,Function<String,ClassInfo> schema,Map<String,Object> input,Runnable check){plan=new QueryPlan(parsed);this.schema=schema;this.input=input;this.check=check;}
    public static QueryPlan translateToCypher(ParsedQuery parsed,String namespace,int maxHops,Function<String,ClassInfo> schema,Map<String,Object> parameters,Runnable check){return new QueryTranslator(parsed,schema,parameters,check).translate(namespace,maxHops);}
    private ClassInfo clazz(String name){check.run();return classes.computeIfAbsent(name,schema);}
    private String param(Object value){String key="q"+count++;plan.parameters.put(key,value);return "$"+key;}
    private String binding(String name){if(aliases.containsKey(name)){plan.bindings.putIfAbsent(name,aliases.get(name));return name;}
        var matches=aliases.entrySet().stream().filter(e->e.getValue().equals(name)).map(Map.Entry::getKey).toList();
        if(matches.size()>1)throw invalid("Ambiguous Class reference: "+name);if(matches.size()==1)return binding(matches.getFirst());
        clazz(name);plan.bindings.putIfAbsent(name,name);return name;}
    private Ref scalar(Ref ref){if(ref.field()==null&&scalars.containsKey(ref.object()))return scalars.get(ref.object());return ref;}
    private Field field(Ref original){Ref ref=scalar(original);if(ref.field()==null)throw invalid("Expected Descriptor reference");String b=binding(ref.object());String type=clazz(plan.bindings.get(b)).types.get(ref.field());if(type==null)throw ExceptionHandler.fail("NOT_FOUND","Descriptor declaration not found: "+ref.field());
        for(Field f:plan.fields)if(f.binding.equals(b)&&f.name.equals(ref.field()))return f;Field f=new Field(b,ref.field(),type,"d"+plan.fields.size());plan.fields.add(f);return f;}
    private QueryPlan translate(String namespace,int maxHops){plan.parameters.put("namespaceId",namespace);
        var names=new HashSet<String>();for(Select s:plan.parsed.select()){if(s.function()==null&&s.ref().field()==null&&s.alias()!=null){if(aliases.putIfAbsent(s.alias(),s.ref().object())!=null)throw invalid("Duplicate Object alias");clazz(s.ref().object());}}
        for(Select s:plan.parsed.select())if(s.function()==null&&s.ref().field()!=null&&s.alias()!=null){if(aliases.containsKey(s.alias())||scalars.putIfAbsent(s.alias(),s.ref())!=null)throw invalid("Alias collision");}
        for(Select s:plan.parsed.select()){
            Field f=s.ref().field()==null?null:field(s.ref());String b=f==null?binding(s.function()==null&&s.alias()!=null?s.alias():s.ref().object()):f.binding;String type=f==null?"Object":f.type;
            String name=s.alias()!=null?s.alias():s.ref().object()+(s.ref().field()==null?"":"."+s.ref().field());if(!names.add(name))throw invalid("Duplicate output name: "+name);
            String fn=s.function();if(fn!=null){if(fn.equals("COLLECT")){if(f==null||plan.parsed.select().size()!=1||plan.parsed.distinct())throw invalid("COLLECT must be a standalone Descriptor projection");plan.collect=true;}
                else {plan.aggregate=true;if(fn.equals("COUNT"))type="Integer";else {if(f==null)throw typeError("Aggregate requires a Descriptor");if(Set.of("SUM","AVG","STDEV","PERCENTILE").contains(fn)){if(!Set.of("Integer","Float").contains(type))throw typeError("Numeric aggregate requires numeric values");if(!fn.equals("SUM"))type="Float";}else if(!comparable(type))throw typeError("MIN/MAX requires a comparable type");}}}
            plan.projections.add(new Projection(s,f,b,name,type));
        }
        resolvePredicates(plan.parsed.where());if(!used.equals(input.keySet()))throw invalid("Unreferenced query parameter");
        for(Order order:plan.parsed.order()){int found=-1;for(int i=0;i<plan.projections.size();i++){Projection p=plan.projections.get(i);if(order.ref().field()==null&&order.ref().object().equals(p.name)||order.ref().equals(p.item.ref())&&p.item.function()==null){if(found!=-1)throw invalid("Ambiguous ORDER BY");found=i;}}
            if(found<0)throw invalid("ORDER BY must reference a selected item");if(!comparable(plan.projections.get(found).type))throw typeError("Column is not orderable");plan.order.add(found);}
        var variables=new LinkedHashMap<String,String>();int n=0;for(String b:plan.bindings.keySet())variables.put(b,"o"+n++);
        var sql=new StringBuilder("CYPHER 25 MATCH ");var matches=new ArrayList<String>();for(var e:variables.entrySet()){String classId=clazz(plan.bindings.get(e.getKey())).id;matches.add("(scope:Namespace {id:$namespaceId})-[:CONTAINS]->("+e.getValue()+":Object:DepositLive {namespaceId:$namespaceId})-[:MEMBER_OF {namespaceId:$namespaceId}]->(:Class:DepositLive {namespaceId:$namespaceId,id:"+param(classId)+"})<-[:CONTAINS]-(scope)");}sql.append(String.join(" MATCH ",matches));
        var relations=new ArrayList<Relation>();gather(plan.parsed.where(),relations);
        if(relations.isEmpty()&&variables.size()>1){String first=variables.keySet().iterator().next();var auto=new ArrayList<String>();for(String b:variables.keySet())if(!b.equals(first))auto.add(relation(new Relation(first,b,null,1,maxHops,0),variables));sql.append(" WHERE ").append(String.join(" AND ",auto));}
        else if(!relations.isEmpty())validateConnectivity(plan.parsed.where(),variables.keySet());
        for(Field f:plan.fields){String v=variables.get(f.binding);sql.append(" OPTIONAL MATCH (").append(v).append(")-[:HAS_DESCRIPTOR]->(").append(f.variable).append(":Descriptor:DepositLive {namespaceId:$namespaceId,ownerId:").append(v).append(".id,name:").append(param(f.name)).append("})");}
        var returns=new ArrayList<String>();returns.addAll(variables.values());for(Field f:plan.fields)returns.add(f.variable);
        for(Relation r:relations){String key="r"+plan.relations.size();plan.relations.put(r,key);returns.add(relation(r,variables)+" AS "+key);}
        sql.append(" RETURN ").append(String.join(",",returns)).append(" ORDER BY ").append(String.join(",",variables.values().stream().map(v->v+".id").toList()));plan.cypher=sql.toString();return plan;
    }
    private void resolvePredicates(Expr expr){check.run();if(expr instanceof Logic l){resolvePredicates(l.left());if(l.right()!=null)resolvePredicates(l.right());}else if(expr instanceof Predicate p){Field f=field(p.ref());plan.predicates.put(p,f);if(!p.op().startsWith("IS")){if(Set.of("<",">","<=",">=").contains(p.op())&&!comparable(f.type))throw typeError("Descriptor supports equality only");Object value=normalize(p.value(),f.type,p.op().equals("IN"));plan.values.put(p,value);}}}
    private Object normalize(Object value,String type,boolean list){if(value instanceof Parameter p){used.add(p.name());if(!input.containsKey(p.name()))throw invalid("Missing query parameter: "+p.name());String usage=type+(list?"[]":"");if(parameterTypes.putIfAbsent(p.name(),usage)!=null&&!parameterTypes.get(p.name()).equals(usage))throw typeError("Parameter used with incompatible types");value=input.get(p.name());}
        if(list){if(value==null)return null;if(!(value instanceof List<?> values))throw typeError("IN requires a list");var result=new ArrayList<Object>();for(Object item:values)result.add(normalize(item,type,false));return result;}return ValueCodec.normalize(type,value);}
    private String endpoint(String name,Map<String,String> vars,Map<String,String> locals){String b=outer(name);if(b!=null)return vars.get(b);return locals.computeIfAbsent(name,k->"e"+count++);}
    private String outer(String name){if(plan.bindings.containsKey(name))return name;var found=plan.bindings.entrySet().stream().filter(e->e.getValue().equals(name)).map(Map.Entry::getKey).toList();if(found.size()>1)throw invalid("Ambiguous relationship endpoint");return found.isEmpty()?null:found.getFirst();}
    private String relation(Relation r,Map<String,String> vars){var locals=new LinkedHashMap<String,String>();String a=endpoint(r.left(),vars,locals),b=endpoint(r.right(),vars,locals);String path="p"+count++;String left=r.direction()<0?"<-":"-",right=r.direction()>0?"->":"-";
        var s=new StringBuilder("EXISTS { MATCH "+path+"=("+a+")"+left+"[:RELATED_TO*"+r.min()+".."+r.max()+"]"+right+"("+b+")");
        for(var e:locals.entrySet())s.append(" MATCH (").append(e.getValue()).append(":Object:DepositLive {namespaceId:$namespaceId})-[:MEMBER_OF {namespaceId:$namespaceId}]->(:Class:DepositLive {namespaceId:$namespaceId,id:").append(param(clazz(e.getKey()).id)).append("})");
        s.append(" WHERE all(x IN nodes(").append(path).append(") WHERE x:Object AND x:DepositLive AND x.namespaceId=$namespaceId) AND all(e IN relationships(").append(path).append(") WHERE e.namespaceId=$namespaceId");if(r.label()!=null)s.append(" AND e.label=").append(param(r.label()));return s.append(") }").toString();}
    private static void gather(Expr e,List<Relation> result){if(e instanceof Relation r)result.add(r);if(e instanceof Logic l){gather(l.left(),result);gather(l.right(),result);}}
    private List<Set<String>> alternatives(Expr e){return alternatives(e,false);}
    private List<Set<String>> alternatives(Expr e,boolean negated){check.run();if(e instanceof Relation r){String a=outer(r.left()),b=outer(r.right());return List.of(a==null||b==null?Set.of():Set.of(a+"\u0000"+b));}if(e instanceof Logic l){if(l.op().equals("NOT"))return alternatives(l.left(),!negated);var a=alternatives(l.left(),negated);var b=alternatives(l.right(),negated);var result=new ArrayList<Set<String>>();if(l.op().equals(negated?"AND":"OR")){result.addAll(a);result.addAll(b);if(result.size()>4096)throw ExceptionHandler.fail("LIMIT_EXCEEDED","Query Boolean planning complexity exceeded");}else for(var x:a)for(var y:b){check.run();var set=new HashSet<>(x);set.addAll(y);result.add(set);if(result.size()>4096)throw ExceptionHandler.fail("LIMIT_EXCEEDED","Query Boolean planning complexity exceeded");}return result;}return List.of(Set.of());}
    private void validateConnectivity(Expr e,Set<String> bindings){for(var edges:alternatives(e)){var reached=new HashSet<String>();reached.add(bindings.iterator().next());boolean changed;do{changed=false;for(String edge:edges){String[] pair=edge.split("\u0000",-1);if(reached.contains(pair[0])||reached.contains(pair[1])){changed|=reached.add(pair[0]);changed|=reached.add(pair[1]);}}}while(changed);if(!reached.containsAll(bindings))throw invalid("Disconnected outer bindings in Boolean alternative");}}
    public static boolean comparable(String type){return !Set.of("List","DDict","Point","DGeometry","DResource").contains(type);}
    public static RuntimeException typeError(String message){return ExceptionHandler.fail("TYPE_CONFLICT",message);}
}
