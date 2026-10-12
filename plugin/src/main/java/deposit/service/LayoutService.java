package deposit.service;

import deposit.common.*;
import deposit.repository.CoreRepository;
import java.util.*;
import org.neo4j.graphdb.*;

/** Deterministic, viewer-only layout computation over a selected induced graph. */
final class LayoutService extends BaseService {
    LayoutService(RequestContext c){super(c);}
    Map<String,Object> layout(Map<String,Object> request){
        fields(request,"selection","options");readContext();scoped("viewer");
        Object selection=request.get("selection");Map<String,Object> options=request.get("options")==null?Map.of():ValueCodec.map(request.get("options"));
        if(!options.isEmpty())c.fail("VALIDATION_FAILED","No layout options are supported");
        List<Node> allClasses=repo.nodes("Class",Map.of("namespaceId",c.namespaceId)),allObjects=repo.nodes("Object",Map.of("namespaceId",c.namespaceId));
        String kind="Class";Set<String> ids=new TreeSet<>();
        if(selection!=null){Map<String,Object> selected=ValueCodec.map(selection);fields(selected,"ids");if(!selected.containsKey("ids"))c.fail("VALIDATION_FAILED","selection.ids is required");Object raw=selected.get("ids");if(!(raw instanceof List<?> list))c.fail("VALIDATION_FAILED","selection.ids must be a list");List<?> list=(List<?>)raw;
            if(!list.isEmpty()){
                Node firstClass=repo.entity("Class",c.namespaceId,RequestContext.uuid(list.getFirst())),firstObject=repo.entity("Object",c.namespaceId,RequestContext.uuid(list.getFirst()));
                if(firstClass!=null&&firstObject!=null)c.fail("VALIDATION_FAILED","Selected ID resolves to both Class and Object");if(firstClass==null&&firstObject==null)c.fail("NOT_FOUND","Selected entity not found");kind=firstClass!=null?"Class":"Object";
            }else return Json.map("positions",Map.of());
            for(Object value:list){String id=RequestContext.uuid(value);Node clazz=repo.entity("Class",c.namespaceId,id),object=repo.entity("Object",c.namespaceId,id);if(clazz!=null&&object!=null)c.fail("VALIDATION_FAILED","Selected ID resolves to both Class and Object");if((kind.equals("Class")?clazz:object)==null)c.fail("VALIDATION_FAILED","Selection must contain one entity kind from the active Namespace");if(!ids.add(id))c.fail("VALIDATION_FAILED","Duplicate selected ID");}
        }else for(Node node:allClasses)ids.add(CoreRepository.id(node));
        if(ids.isEmpty())return Json.map("positions",Map.of());
        Map<String,SortedSet<String>> edges=new TreeMap<>();for(String id:ids)edges.put(id,new TreeSet<>());
        String relationshipKind=kind.equals("Class")?"ClassRelationship":"Relationship";
        for(Relationship edge:repo.relationships(c.namespaceId,relationshipKind)){c.state.kernel.assertOpen();String source=CoreRepository.id(edge.getStartNode()),target=CoreRepository.id(edge.getEndNode());if(ids.contains(source)&&ids.contains(target))edges.get(source).add(target);}
        List<List<String>> components=scc(edges);Map<String,Integer> componentOf=new HashMap<>();for(int i=0;i<components.size();i++)for(String id:components.get(i))componentOf.put(id,i);
        List<SortedSet<Integer>> dag=new ArrayList<>();int[] indegree=new int[components.size()];for(int i=0;i<components.size();i++)dag.add(new TreeSet<>());for(var edge:edges.entrySet())for(String target:edge.getValue()){int a=componentOf.get(edge.getKey()),b=componentOf.get(target);if(a!=b&&dag.get(a).add(b))indegree[b]++;}
        Comparator<Integer> order=Comparator.comparing(i->components.get(i).getFirst());PriorityQueue<Integer> ready=new PriorityQueue<>(order);for(int i=0;i<indegree.length;i++)if(indegree[i]==0)ready.add(i);int[] layer=new int[components.size()];while(!ready.isEmpty()){c.state.kernel.assertOpen();int current=ready.remove();for(int next:dag.get(current)){layer[next]=Math.max(layer[next],layer[current]+1);if(--indegree[next]==0)ready.add(next);}}
        Map<String,Object> positions=new TreeMap<>();Map<Integer,Integer> lane=new HashMap<>();List<Integer> sorted=new ArrayList<>();for(int i=0;i<components.size();i++)sorted.add(i);sorted.sort(Comparator.comparingInt((Integer i)->layer[i]).thenComparing(order));
        for(int component:sorted){int y=lane.merge(layer[component],1,Integer::sum)-1;for(String id:components.get(component))positions.put(id,List.of(layer[component]*240.0,y*120.0));}
        return Json.map("positions",positions);
    }
    private List<List<String>> scc(Map<String,SortedSet<String>> graph){Map<String,Integer> index=new HashMap<>(),low=new HashMap<>();Deque<String> stack=new ArrayDeque<>();Set<String> onStack=new HashSet<>();List<List<String>> out=new ArrayList<>();int[] next={0};for(String id:graph.keySet())if(!index.containsKey(id))visit(id,graph,index,low,stack,onStack,out,next);out.forEach(Collections::sort);out.sort(Comparator.comparing(a->a.getFirst()));return out;}
    private void visit(String v,Map<String,SortedSet<String>> graph,Map<String,Integer> index,Map<String,Integer> low,Deque<String> stack,Set<String> onStack,List<List<String>> out,int[] next){c.state.kernel.assertOpen();index.put(v,next[0]);low.put(v,next[0]++);stack.push(v);onStack.add(v);for(String w:graph.get(v)){if(!index.containsKey(w)){visit(w,graph,index,low,stack,onStack,out,next);low.put(v,Math.min(low.get(v),low.get(w)));}else if(onStack.contains(w))low.put(v,Math.min(low.get(v),index.get(w)));}if(low.get(v).equals(index.get(v))){List<String> component=new ArrayList<>();String w;do{w=stack.pop();onStack.remove(w);component.add(w);}while(!w.equals(v));out.add(component);}}
}
