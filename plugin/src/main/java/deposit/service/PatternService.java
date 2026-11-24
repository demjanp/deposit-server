package deposit.service;

import deposit.common.*;
import deposit.common.Results.DataRow;
import deposit.repository.CoreRepository;
import org.neo4j.graphdb.*;
import java.util.*;
import java.util.stream.*;

/** Pattern canonicalization, Namespace compatibility and deterministic structural binding. */
public final class PatternService extends BaseService {
    public PatternService(RequestContext c) {super(c);}

    public static Map<String,Object> normalizeDefinition(Map<String,Object> source) {
        return PatternCodec.normalize(source);
    }
    public void validateNamespace(String namespaceId){new PatternCompatibilityService(c).validateNamespace(namespaceId);}
    private Node pattern(Object selector){Node n=null;if(selector instanceof Map<?,?> m){var map=ValueCodec.map(m);fields(map,"id");n=repo.entity("Pattern",c.namespaceId,RequestContext.uuid(map.get("id")));}else{String s=string(selector);n=repo.find("Pattern",Map.of("namespaceId",c.namespaceId,"name",name(s)));}if(n==null)c.fail("NOT_FOUND","Pattern not found");return n;}
    private List<Map<String,Object>> bindings(Node pattern,Object values){return bindings(pattern,values,Map.of());}
    private List<Map<String,Object>> bindings(Node pattern,Object values,Map<String,Object> rules){return bindings(pattern,values,rules,null,false);}
    private List<Map<String,Object>> bindings(Node pattern,Object values,Map<String,Object> rules,Set<String> selected,boolean firstOnly){
        Map<String,Object> definition=Json.read((String)pattern.getProperty("definition"));Map<String,Object> roles=ValueCodec.map(definition.get("objects"));Map<String,Object> supplied=values==null?Map.of():ValueCodec.map(values);for(String key:supplied.keySet())if(!roles.containsKey(key))c.fail("VALIDATION_FAILED","Unknown Pattern role in values");var classes=new HashMap<String,Node>();
        for(var entry:roles.entrySet()) {
            assertMatchingOpen();
            String name=(String)ValueCodec.map(entry.getValue()).get("className");
            if(!classes.containsKey(name))classes.put(name,repo.find("Class",Map.of("namespaceId",c.namespaceId,"name",name)));
        }
        var probes=searchValues(roles,supplied,classes);
        var result=new ArrayList<Map<String,Object>>();
        var domains=new TreeMap<String,List<Node>>();
        for(var entry:roles.entrySet()){var spec=ValueCodec.map(entry.getValue());Node clazz=classes.get(spec.get("className"));var domain=new ArrayList<Node>();
            SearchValues probe=probes.get(entry.getKey());
            boolean matchEmpty=Boolean.TRUE.equals(rules.getOrDefault("matchEmpty",spec.get("matchEmpty")));
            if(clazz!=null)try(var links=clazz.getRelationships(Direction.INCOMING,RelationshipType.withName("MEMBER_OF"))){for(var link:links){Node candidate=link.getStartNode();assertMatchingOpen();if(selected!=null&&!selected.contains(CoreRepository.id(candidate)))continue;if(probe!=null&&!valueMatch(candidate,probe,matchEmpty))continue;
                if(domain.size()>=1000)c.fail("LIMIT_EXCEEDED","Pattern role exceeds 1000 candidates");domain.add(candidate);}}
            domain.sort(Comparator.comparing(CoreRepository::id));domains.put(entry.getKey(),domain);
        }
        for(String root:roles.keySet().stream().filter(k->Boolean.TRUE.equals(ValueCodec.map(roles.get(k)).get("root"))).sorted().toList()){
            Set<String> component=component(root,roles);var assignment=new LinkedHashMap<String,Node>();bindComponent(root,component,roles,assignment,result,supplied,rules,domains,firstOnly);if(firstOnly&&!result.isEmpty())break;
        }
        result.sort(Comparator.comparing((Map<String,Object> row)->(String)row.get("rootName"))
            .thenComparing(row->Json.write(row.get("bindings"))));
        return result;
    }
    private record SearchValue(String type,Object value) {}
    private record SearchValues(Set<String> scope,Map<String,SearchValue> expected) {}
    private Map<String,SearchValues> searchValues(Map<String,Object> roles,Map<String,Object> values,Map<String,Node> classes) {
        var probes=new HashMap<String,SearchValues>();
        for(var entry:values.entrySet()) {
            var role=ValueCodec.map(roles.get(entry.getKey()));
            Node clazz=classes.get(role.get("className"));
            var scope=new HashMap<String,String>();
            for(Object raw:(List<?>)role.get("descriptors")) {
                var descriptor=ValueCodec.map(raw);
                scope.put((String)descriptor.get("name"),(String)descriptor.get("type"));
            }
            var expected=new LinkedHashMap<String,SearchValue>();
            for(var value:ValueCodec.map(entry.getValue()).entrySet()) {
                assertMatchingOpen();
                String field=value.getKey();
                if(!scope.containsKey(field))c.fail("VALIDATION_FAILED","Pattern value is outside the role's declared Descriptor scope: "+field);
                Node declaration=clazz==null?null:repo.declaration(clazz,field);
                String type=declaration==null?scope.get(field):(String)declaration.getProperty("type");
                if(type==null)c.fail("VALIDATION_FAILED","Pattern value has no declaration: "+field);
                try {expected.put(field,new SearchValue(type,ValueCodec.normalize(type,value.getValue())));}
                catch(RuntimeException failure) {c.fail("TYPE_CONFLICT","Pattern value does not match its declaration: "+field);}
            }
            probes.put(entry.getKey(),new SearchValues(scope.keySet(),expected));
        }
        return probes;
    }
    private Set<String> component(String root,Map<String,Object> roles){var out=new LinkedHashSet<String>();var q=new ArrayDeque<String>();q.add(root);out.add(root);while(!q.isEmpty()){String x=q.remove();for(Object raw:(List<?>)ValueCodec.map(roles.get(x)).get("relationships")){String t=(String)ValueCodec.map(raw).get("targetName");if(out.add(t))q.add(t);}for(var e:roles.entrySet())for(Object raw:(List<?>)ValueCodec.map(e.getValue()).get("relationships"))if(Objects.equals(ValueCodec.map(raw).get("targetName"),x)&&out.add(e.getKey()))q.add(e.getKey());}return out;}
    private void bindComponent(String root,Set<String> component,Map<String,Object> roles,LinkedHashMap<String,Node> assigned,List<Map<String,Object>> out,Map<String,Object> values,Map<String,Object> rules,Map<String,List<Node>> domains,boolean firstOnly){
        var order=new ArrayList<>(component);int[] next=new int[order.size()];int depth=0;
        while(depth>=0){assertMatchingOpen();String role=order.get(depth);List<Node> domain=domains.get(role);
            if(next[depth]>=domain.size()){next[depth]=0;assigned.remove(role);depth--;if(depth>=0)assigned.remove(order.get(depth));continue;}
            Node candidate=domain.get(next[depth]++);if(!compatible(candidate,role,roles,assigned))continue;assigned.put(role,candidate);
            if(depth==order.size()-1){if(edgesSatisfied(roles,assigned)){out.add(bindingResult(roles,assigned,root));if(firstOnly)return;}assigned.remove(role);}
            else depth++;
        }
    }
    private boolean connectedToAssigned(String role,Map<String,Object> roles,Map<String,Node> assigned){for(var e:assigned.entrySet()){for(Object raw:(List<?>)ValueCodec.map(roles.get(e.getKey())).get("relationships"))if(role.equals(ValueCodec.map(raw).get("targetName")))return true;for(Object raw:(List<?>)ValueCodec.map(roles.get(role)).get("relationships"))if(e.getKey().equals(ValueCodec.map(raw).get("targetName")))return true;}return false;}
    private boolean compatible(Node candidate,String role,Map<String,Object> roles,Map<String,Node> assigned){for(var e:assigned.entrySet()){for(Object raw:(List<?>)ValueCodec.map(roles.get(e.getKey())).get("relationships")){var edge=ValueCodec.map(raw);if(role.equals(edge.get("targetName"))&&!hasEdge(e.getValue(),candidate,(String)edge.get("label")))return false;}for(Object raw:(List<?>)ValueCodec.map(roles.get(role)).get("relationships")){var edge=ValueCodec.map(raw);if(e.getKey().equals(edge.get("targetName"))&&!hasEdge(candidate,e.getValue(),(String)edge.get("label")))return false;}}return true;}
    private boolean edgesSatisfied(Map<String,Object> roles,Map<String,Node> assigned){
        for(var role:roles.entrySet()){
            Node source=assigned.get(role.getKey());
            if(source==null)continue;
            for(Object raw:(List<?>)ValueCodec.map(role.getValue()).get("relationships")){
                var edge=ValueCodec.map(raw);Node target=assigned.get(edge.get("targetName"));
                if(source==null||target==null||!hasEdge(source,target,(String)edge.get("label")))return false;
            }
        }
        return true;
    }
    private Map<String,Object> bindingResult(Map<String,Object> roles,Map<String,Node> assigned,String root){var ids=new TreeMap<String,String>();assigned.forEach((k,v)->ids.put(k,CoreRepository.id(v)));return Json.map("rootName",root,"bindings",ids);}
    private boolean hasEdge(Node a,Node b,String label){assertMatchingOpen();return repo.relationship(a,b,label)!=null;}
    private void assertMatchingOpen(){c.state.kernel.assertOpen();if(System.currentTimeMillis()-c.state.kernel.startTime()>30_000)c.fail("CANCELLED","Pattern matching timeout exceeded");}
    private void stable() {
        assertMatchingOpen();
        if(currentReadRevision("viewer")!=c.revision)c.fail("REVISION_CONFLICT","Revision changed during Pattern search");
    }
    private boolean valueMatch(Node object,SearchValues probe,boolean matchEmpty) {
        if(matchEmpty) {
            var actual=new HashSet<String>();
            for(Node descriptor:repo.children(object,"Descriptor")) {
                assertMatchingOpen();
                String name=(String)descriptor.getProperty("name");
                if(probe.scope().contains(name))actual.add(name);
            }
            if(!actual.equals(probe.expected().keySet()))return false;
        }
        for(var entry:probe.expected().entrySet()) {
            assertMatchingOpen();
            SearchValue value=entry.getValue();
            if(value.value()==null&&!matchEmpty)continue;
            Node descriptor=repo.descriptor(object,entry.getKey());
            if(descriptor==null||!Objects.equals(value.value(),repo.descriptorValue(descriptor,value.type())))return false;
        }
        return true;
    }
    public Set<String> applicablePatterns(Set<String> selectedObjectIds){var result=new TreeSet<String>();applicablePatterns(selectedObjectIds,result);return result;}
    public void applicablePatterns(Set<String> selectedObjectIds,Set<String> result){try(var patterns=repo.scanNodes("Pattern",Map.of("namespaceId",c.namespaceId))){while(patterns.hasNext()){Node pattern=patterns.next();if(!bindings(pattern,Map.of(),Map.of(),selectedObjectIds,true).isEmpty())result.add(CoreRepository.id(pattern));}}}
    public Stream<DataRow> search(Map<String,Object> request,boolean page){
        readContext();pinReadScope("viewer");fields(request,page?new String[]{"pattern","values","rules","pageSize","cursor"}:new String[]{"pattern","values","rules"});Map<String,Object> rules=request.get("rules")==null?Map.of():ValueCodec.map(request.get("rules"));fields(rules,"matchEmpty");if(rules.containsKey("matchEmpty")&&!(rules.get("matchEmpty") instanceof Boolean))c.fail("VALIDATION_FAILED","matchEmpty must be Boolean");Node selected=pattern(request.get("pattern"));Object values=request.get("values");
        if(!page) {
            var matches=bindings(selected,values,rules);
            stable();
            var iterator=matches.iterator();
            var rows=new Spliterators.AbstractSpliterator<DataRow>(matches.size(),Spliterator.ORDERED) {
                @Override public boolean tryAdvance(java.util.function.Consumer<? super DataRow> action) {
                    stable();
                    if(!iterator.hasNext())return false;
                    var match=iterator.next();
                    var bound=new TreeMap<String,Object>();
                    for(var entry:ValueCodec.map(match.get("bindings")).entrySet()) {
                        assertMatchingOpen();
                        bound.put(entry.getKey(),repo.entity("Object",c.namespaceId,(String)entry.getValue()));
                    }
                    action.accept(new DataRow(Json.map("rootName",match.get("rootName"),"bindings",bound,"conflicts",List.of()),c.meta()));
                    stable();
                    return true;
                }
            };
            return StreamSupport.stream(rows,false).onClose(this::stable);
        }
        Map<String,Object> p=request;long size=integer(p.getOrDefault("pageSize",200L));if(size<1||size>1000)c.fail("LIMIT_EXCEEDED","pageSize must be between 1 and 1000");long offset=0;String hash=Json.hash(Json.write(Json.map("pattern",CoreRepository.id(selected),"values",values,"rules",request.get("rules"))));
        if(p.containsKey("cursor")){var cursor=CursorCodec.decode(c,string(p.get("cursor")));if(!c.namespaceId.equals(cursor.get("scope"))||!c.actorId.equals(cursor.get("actor"))||!hash.equals(cursor.get("hash"))||((Number)cursor.get("revision")).longValue()!=c.revision)c.fail("REVISION_CONFLICT","Pattern cursor is stale or out of scope");offset=((Number)cursor.get("offset")).longValue();}
        var matches=bindings(selected,values,rules);
        if(offset<0||offset>matches.size())c.fail("VALIDATION_FAILED","Invalid cursor offset");int end=(int)Math.min(matches.size(),offset+size);var data=Json.map("items",matches.subList((int)offset,end),"conflicts",List.of());var meta=c.meta();if(end<matches.size())meta.put("nextCursor",CursorCodec.encode(c,Json.map("scope",c.namespaceId,"actor",c.actorId,"hash",hash,"revision",c.revision,"offset",end,"expires",System.currentTimeMillis()+900_000L,"run",c.server.runId)));stable();return Stream.of(new DataRow(data,meta));
    }
}
