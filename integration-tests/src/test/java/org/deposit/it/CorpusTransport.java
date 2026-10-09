package org.deposit.it;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;
import org.neo4j.driver.*;
import org.neo4j.driver.internal.InternalRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Migration transport for the cumulative scientific corpus. Fixture maps are test data,
 * never public envelopes: every wire call uses Stage 6.5 positional arguments and prepared
 * actions. Rich assertion rows are explicitly assembled from portable conversion/receipts.
 * Stage65CoreIT independently checks the actual public columns without this adapter.
 */
final class CorpusTransport {
    static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_LONG_FOR_INTS);
    static final Map<String,List<String>> BINDINGS=CorpusCatalog.BINDINGS;
    static final Map<String,String> OUTPUTS=CorpusCatalog.OUTPUTS;
    static final Set<String> ACTIONS=CorpusCatalog.ACTIONS;
    static final Map<String,String> OPERATIONS=new ConcurrentHashMap<>();
    static final Map<String,String> REQUEST_TOKENS=new ConcurrentHashMap<>();
    final Driver raw;
    CorpusTransport(Driver raw){this.raw=raw;}
    static Driver wrap(Driver raw){var transport=new CorpusTransport(raw);return transport.proxy(raw,Driver.class,new Frame(false));}
    static final class Frame {final boolean transaction;boolean mutation;Frame(boolean transaction){this.transaction=transaction;}}
    @SuppressWarnings("unchecked") <T>T proxy(T target,Class<T> type,Frame frame){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(object,method,args)->{
        try{
            if(method.getName().equals("run")&&args!=null&&args.length>=1&&(args[0] instanceof String||args[0] instanceof Query)){
                String text=args[0] instanceof Query q?q.text():(String)args[0];Map<String,Object> parameters=args[0] instanceof Query q?q.parameters().asMap():args.length>1&&args[1] instanceof Map<?,?>?(Map<String,Object>)args[1]:Map.of();
                Translation translated=translate(text,parameters,target,frame);
                if(translated==null&&text.contains("operationId")&&!text.contains("CALL deposit.")){
                    var mapped=new LinkedHashMap<String,Object>(parameters);mapped.replaceAll((key,value)->value instanceof String id?resolveOperation(id):value);return run(target,text,mapped);
                }
                if(translated!=null){
                    Object[] actual=args.clone();actual[0]=args[0] instanceof Query?new Query(translated.text,translated.parameters):translated.text;
                    if(!(args[0] instanceof Query)){
                        if(args.length>1&&args[1] instanceof Map<?,?>)actual[1]=translated.parameters;
                        else {Result result=run(target,translated.text,translated.parameters);return result(result,translated,frame);}
                    }
                    return result((Result)method.invoke(target,actual),translated,frame);
                }
            }
            Object value=method.invoke(target,args);
            if(value instanceof Session s)return proxy(s,Session.class,new Frame(false));
            if(value instanceof Transaction tx)return proxy(tx,Transaction.class,new Frame(true));
            return value;
        }catch(InvocationTargetException e){throw e.getCause();}
    });}
    String resolveOperation(String key){
        String cached=OPERATIONS.get(key);if(cached!=null)return cached;
        try(var session=raw.session()){
            var rows=session.run("MATCH (a:DepositPreparedAction {recoveryKey:$key}) RETURN a.operationId AS id",Stage1LifecycleIT.m("key",key)).list();
            if(rows.isEmpty())return key;String operation=rows.getFirst().get("id").asString();OPERATIONS.put(key,operation);return operation;
        }
    }
    static Result run(Object runner,String query,Map<String,Object> parameters){return runner instanceof Transaction tx?tx.run(query,parameters):((Session)runner).run(query,parameters);}
    org.neo4j.driver.Record direct(String procedure,Object... arguments){var params=new LinkedHashMap<String,Object>();var names=new ArrayList<String>();for(int i=0;i<arguments.length;i++){params.put("a"+i,arguments[i]);names.add("$a"+i);}try(var s=raw.session()){return s.run("CYPHER 25 CALL "+procedure+"("+String.join(",",names)+")"+(procedure.equals("deposit.get.requests")?" YIELD data RETURN data LIMIT 1":""),params).single();}}
    static String renamed(String p){return Set.of("deposit.set.classDescriptorOrder","deposit.set.classDescriptorComment","deposit.set.classDescriptorType").contains(p)?"deposit.set.classDescriptor":p;}
    @SuppressWarnings("unchecked") static List<Object> arguments(String procedure,Map<String,Object> input){
        String p=renamed(procedure);var request=new LinkedHashMap<>(input);var result=new ArrayList<Object>();
        if(p.equals("deposit.apply.batch")){
            var commands=new ArrayList<Object>();for(Object item:(List<?>)request.getOrDefault("commands",List.of())){var command=(Map<String,Object>)item;String name=(String)command.get("procedure");commands.add(Stage1LifecycleIT.m("key",command.get("key"),"procedure",renamed(name),"arguments",arguments(name,(Map<String,Object>)command.get("request"))));}return List.of(commands);
        }
        for(String binding:BINDINGS.get(p)){
            if(binding.equals("+")){result.add(new LinkedHashMap<>(request));request.clear();}
            else if(binding.startsWith("@")){
                String[] parts=binding.substring(1).split(":");String field=parts[1];Object id=request.remove(field);
                if(id!=null)result.add(id instanceof Map<?,?>?id:Stage1LifecycleIT.m("id",id));
                else if(field.equals("classId"))result.add(request.remove("className"));
                else result.add(null);
            }else if(binding.startsWith("?")){
                var selection=new LinkedHashMap<>(request);if(p.endsWith("Page")){selection.remove("pageSize");selection.remove("cursor");}selection.keySet().forEach(request::remove);
                if(binding.equals("?User")&&selection.containsKey("userId")){selection.put("id",selection.remove("userId"));}
                result.add(selection.size()==1&&selection.containsKey("name")?selection.get("name"):selection);
            }else{String key=binding.startsWith("~")?binding.substring(1):binding;Object value=request.remove(key);if(key.equals("scope")&&value==null)value="namespace";if(key.equals("parameters")&&value==null)value=Map.of();result.add(value);}
        }
        return result;
    }
    record Translation(String text,Map<String,Object> parameters,String procedure,String token,Map<String,Object> request,boolean implicit){}
    @SuppressWarnings("unchecked") Translation translate(String text,Map<String,Object> input,Object runner,Frame frame){
        var matcher=Pattern.compile("CALL\\s+(deposit\\.[A-Za-z.]+)\\s*\\(").matcher(text);if(!matcher.find())return null;
        String old=matcher.group(1);int open=matcher.end()-1,close=matching(text,open);List<String> expressions=split(text.substring(open+1,close));
        boolean unauth=Set.of("deposit.utils.login","deposit.utils.bootstrap").contains(old);
        if(unauth?expressions.size()!=1:expressions.size()!=2)return null;
        if(!unauth&&!expressions.getLast().matches("\\$[A-Za-z][A-Za-z0-9_]*"))return null;
        Object context=unauth?null:input.get(expressions.getLast().substring(1));if(!unauth&&!(context instanceof Map<?,?>))return null;
        Map<String,Object> request=(Map<String,Object>)run(runner,"CYPHER 25 RETURN "+expressions.getFirst()+" AS input",input).single().get("input").asObject();
        var ctx=unauth?new LinkedHashMap<String,Object>():(Map<String,Object>)context;String token=(String)ctx.get("token"),procedure=renamed(old);
        if(ctx.get("requestId") instanceof String rid&&token!=null)REQUEST_TOKENS.put(rid,token);
        if(procedure.equals("deposit.utils.cancel")){
            String target=REQUEST_TOKENS.get((String)request.get("requestId"));
            if(target!=null&&target.equals(token))request=Map.of();
            else if(target!=null){
                try{var active=direct("deposit.get.requests",target);request=Stage1LifecycleIT.m("requestId",active.get("data").get("requestId").asString());procedure="deposit.utils.cancelRequest";}catch(org.neo4j.driver.exceptions.NoSuchRecordException ignored){request=Map.of();}
            }else request=Map.of();
        }
        var args=arguments(procedure,request);String output=OUTPUTS.get(procedure);boolean action=ACTIONS.contains(procedure)&&(!procedure.equals("deposit.admin.pruneResources")||Boolean.FALSE.equals(request.get("dryRun")));
        Map<String,Object> session=Map.of();
        if(!unauth&&!Set.of("deposit.utils.cancel","deposit.utils.cancelRequest").contains(procedure))session=run(runner,"CYPHER 25 CALL deposit.get.session($t) YIELD data RETURN data",Stage1LifecycleIT.m("t",token)).single().get("data").asMap();
        String operation=null;boolean replay=false;
        if(action&&!frame.mutation&&ctx.get("operationId") instanceof String key){
            direct("deposit.action.release",token);
            String scope=CorpusCatalog.SERVER.contains(procedure)?"server":(String)session.get("namespaceId");
            if(procedure.equals("deposit.admin.pruneResources"))scope="all".equals(request.get("scope"))?"server":(String)session.get("namespaceId");
            if(ctx.get("expectedRevision") instanceof Number expected&&scope!=null){
                long current=scope.equals("server")?((Number)session.get("serverRevision")).longValue():run(runner,"MATCH (n:Namespace {id:$id}) RETURN n.sequence AS revision",Stage1LifecycleIT.m("id",scope)).single().get("revision").asLong();
                if(expected.longValue()==current)direct("deposit.utils.refresh",scope.equals("server")?"server":"namespace",token);
            }
            if(procedure.equals("deposit.delete.namespace")&&request.get("expectedNamespaceRevision") instanceof Number expected){String ns=(String)session.get("namespaceId");if(ns!=null&&run(runner,"MATCH (n:Namespace {id:$id}) RETURN n.sequence AS revision",Stage1LifecycleIT.m("id",ns)).single().get("revision").asLong()==expected.longValue())direct("deposit.utils.refresh","namespace",token);}
            var preparation=direct("deposit.action.prepare",key,procedure,args,token).get("data");operation=preparation.get("operationId").asString();replay="committed".equals(preparation.get("state").asString());OPERATIONS.put(key,operation);
        }
        if(action&&frame.transaction)frame.mutation=true;
        if(procedure.equals("deposit.get.operation")&&request.get("operationId") instanceof String key){
            String actual=OPERATIONS.get(key);if(actual==null){try(var s=raw.session()){var rows=s.run("MATCH (a:DepositPreparedAction {recoveryKey:$key}) RETURN a.operationId AS id",Stage1LifecycleIT.m("key",key)).list();if(!rows.isEmpty())actual=rows.getFirst().get("id").asString();}}
            if(actual!=null)args.set(0,actual);
        }
        var params=new LinkedHashMap<>(input);var placeholders=new ArrayList<String>();for(int i=0;i<args.size();i++){String key="__s65a"+i;params.put(key,args.get(i));placeholders.add("$"+key);}if(!unauth){params.put("__s65token",token);placeholders.add("$__s65token");}
        params.put("__s65replay",replay);params.put("__s65op",operation);params.put("__s65server",session.getOrDefault("serverRevision",0L));params.put("__s65ns",session.get("namespaceId"));
        String suffix=text.substring(close+1);var yield=Pattern.compile("^\\s*YIELD\\s+([\\w, ]+?)(?=\\s+(?:RETURN|MATCH|WITH|WHERE|CALL|LIMIT|OPTIONAL)\\b|$)",Pattern.CASE_INSENSITIVE).matcher(suffix);
        if(yield.find())suffix=suffix.substring(yield.end());
        String columns=switch(output){case "node"->"node";case "rel"->"rel";case "value"->"value";case "query"->"data, columns, presence";case "eligibility"->"value, data";default->"data";};
        StringBuilder body=new StringBuilder(text.substring(0,matcher.start())).append("CALL ").append(procedure).append('(').append(String.join(",",placeholders)).append(") YIELD ").append(columns);
        if(action&&operation!=null){body.append(" OPTIONAL MATCH (__receipt:DepositReceipt {operationId:$__s65op})");if(output.equals("node")||output.equals("rel"))body.append(" WITH *, __receipt.outcome AS data");}
        else if(output.equals("node")||output.equals("rel")){
            String entity=output;body.append(" CALL { WITH ").append(entity).append(" WITH ").append(entity).append(" WHERE ").append(entity).append(" IS NOT NULL CALL deposit.utils.toData(").append(entity).append(",$__s65token) YIELD data RETURN data UNION WITH ").append(entity).append(" WITH ").append(entity).append(" WHERE ").append(entity).append(" IS NULL RETURN null AS data }");
        }
        if(!unauth){
            String namespace=CorpusCatalog.SERVER.contains(procedure)&&!procedure.endsWith("activeNamespace")?"null":"$__s65ns";
            body.append(" OPTIONAL MATCH (__namespace:Namespace {id:").append(namespace).append("}) WITH *, {namespaceId:").append(namespace).append(",revision:");
            if(action&&operation!=null)body.append(CorpusCatalog.SERVER.contains(procedure)?"CASE WHEN $__s65replay THEN $__s65server ELSE __receipt.revision END":"__receipt.revision");
            else body.append(namespace.equals("null")?"$__s65server":"__namespace.sequence");
            body.append(",operationId:$__s65op,requestId:null,nextCursor:").append(output.equals("page")?"data.nextCursor":"null").append("} AS meta");
        }else body.append(" WITH *, {namespaceId:null,revision:null,operationId:null,requestId:null,nextCursor:null} AS meta");
        if(suffix.isBlank())body.append(" RETURN ").append(columns).append((output.equals("node")||output.equals("rel"))?", data":"").append(", meta");else body.append(' ').append(suffix);
        return new Translation(body.toString(),params,procedure,token,request,!frame.transaction);
    }
    Result result(Result result,Translation translation,Frame frame){return (Result)Proxy.newProxyInstance(Result.class.getClassLoader(),new Class<?>[]{Result.class},(proxy,method,args)->{
        try{Object value=method.invoke(result,args);if(value instanceof org.neo4j.driver.Record row)return enrich(row,translation);if(value instanceof List<?> list&&!list.isEmpty()&&list.getFirst() instanceof org.neo4j.driver.Record)return list.stream().map(r->enrich((org.neo4j.driver.Record)r,translation)).toList();return value;}catch(InvocationTargetException e){throw e.getCause();}
    });}
    org.neo4j.driver.Record enrich(org.neo4j.driver.Record row,Translation tr){
        var values=new LinkedHashMap<>(row.asMap());Object data=values.get("data");if(data instanceof String text&&text.startsWith("{")){try{values.put("data",JSON.readValue(text,Map.class));}catch(Exception e){throw new AssertionError(e);}}
        if(tr.implicit&&tr.procedure.equals("deposit.utils.login")&&values.containsKey("value")){
            var info=direct("deposit.get.session",values.get("value")).get("data").asMap();values.put("data",Stage1LifecycleIT.m("user",info.get("user"),"expiresAt",info.get("expiresAt")));values.put("meta",Stage1LifecycleIT.m("namespaceId",null,"revision",info.get("serverRevision"),"operationId",null,"requestId",null,"nextCursor",null));
        }
        if(tr.implicit&&tr.procedure.equals("deposit.utils.bootstrap")){
            String token=direct("deposit.utils.login",tr.request.get("username"),tr.request.get("password")).get("value").asString();
            long revision=direct("deposit.get.session",token).get("data").get("serverRevision").asLong();direct("deposit.utils.logout",token);
            values.put("meta",Stage1LifecycleIT.m("namespaceId",null,"revision",revision,"operationId",null,"requestId",null,"nextCursor",null));
        }
        return new InternalRecord(new ArrayList<>(values.keySet()),values.values().stream().map(Values::value).toList());
    }
    static int matching(String text,int start){int depth=0;char quote=0;for(int i=start;i<text.length();i++){char ch=text.charAt(i);if(quote!=0){if(ch==quote&&text.charAt(i-1)!='\\')quote=0;continue;}if(ch=='\''||ch=='"'){quote=ch;continue;}if(ch=='(')depth++;else if(ch==')'&&--depth==0)return i;}throw new IllegalArgumentException("Unclosed call");}
    static List<String> split(String text){var out=new ArrayList<String>();int depth=0,start=0;char quote=0;for(int i=0;i<text.length();i++){char ch=text.charAt(i);if(quote!=0){if(ch==quote&&(i==0||text.charAt(i-1)!='\\'))quote=0;continue;}if(ch=='\''||ch=='"')quote=ch;else if("({[".indexOf(ch)>=0)depth++;else if(")}]".indexOf(ch)>=0)depth--;else if(ch==','&&depth==0){out.add(text.substring(start,i).trim());start=i+1;}}if(!text.isBlank())out.add(text.substring(start).trim());return out;}
}
