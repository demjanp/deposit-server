package deposit.repository;

import deposit.common.*;
import deposit.common.QueryContracts.*;
import java.util.*;
import java.util.stream.*;
import org.neo4j.graphdb.*;

/** Owns graph reads and repository-result lifetime, always in the injected transaction. */
public final class GetQueryRepository {
    private final Transaction tx;private final CoreRepository core;private final String namespace;
    public GetQueryRepository(RequestContext c){tx=c.tx;core=new CoreRepository(tx,c.timestamp);namespace=c.namespaceId;}
    public ClassInfo schema(String name){Node clazz=core.find("Class",Map.of("namespaceId",namespace,"name",name));if(clazz==null||!clazz.hasLabel(Label.label("DepositLive")))throw ExceptionHandler.fail("NOT_FOUND","Class not found: "+name);
        var types=new LinkedHashMap<String,String>();for(Node d:core.children(clazz,"ClassDescriptor"))if(d.hasLabel(Label.label("DepositLive"))&&core.owner(d).equals(clazz))types.put((String)d.getProperty("name"),(String)d.getProperty("type"));return new ClassInfo(CoreRepository.id(clazz),types);}
    public Stream<Binding> execute(ExecutionPlan plan,Runnable check){check.run();Result result=tx.execute(plan.cypher(),plan.parameters());
        var iterator=new Spliterators.AbstractSpliterator<Binding>(Long.MAX_VALUE,Spliterator.ORDERED){public boolean tryAdvance(java.util.function.Consumer<? super Binding> action){check.run();if(!result.hasNext()){result.close();return false;}var raw=result.next();var objects=new LinkedHashMap<String,Node>();var ids=new ArrayList<String>();int n=0;for(String b:plan.bindings().keySet()){Node node=(Node)raw.get("o"+n++);objects.put(b,node);ids.add(CoreRepository.id(node));}
            var fields=new HashMap<Field,Cell>();
            action.accept(new Binding(objects,field->fields.computeIfAbsent(field,f->{
                check.run();
                Node descriptor=(Node)raw.get(f.variable());
                return new Cell(f.type(),descriptor==null?null:ValueCodec.normalize(f.type(),core.descriptorValue(descriptor,f.type())),descriptor!=null);
            }),raw,ids));
            check.run();return true;}};
        return StreamSupport.stream(iterator,false).onClose(result::close);
    }
    public Map<String,Object> portable(Node object){return core.data(object);}
}
