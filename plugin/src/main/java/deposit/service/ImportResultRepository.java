package deposit.service;

import deposit.common.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import deposit.repository.CoreRepository;
import deposit.repository.ImportResultStore;
import deposit.repository.ImportResultStore.Entry;
import deposit.repository.ImportResultStore.Root;
import deposit.repository.ImportResultStore.Segment;
import org.neo4j.graphdb.Node;

/** Temporary bounded import result collections published after settlement of the enclosing transaction. */
final class ImportResultRepository {
    static final int MAX_ENTRY_BYTES=32*1024, TEXT_CHUNK_BYTES=16*1024, PAGE_LIMIT=256, PAGE_BYTES=512*1024;
    private final RequestContext c;
    private final ImportResultStore results;
    private final List<Segment> segments = new ArrayList<>(64);
    ImportResultRepository(RequestContext context){c=context;results=c.server.storage.importResults;}

    Map<String,Object> save(String operationId,String kind,String planId,long revision,Map<String,Object> data){return save(operationId,kind,planId,revision,c.namespaceId,data);}
    Map<String,Object> saveClone(String operationId,String namespaceId,long revision,Map<String,Object> data){return save(operationId,"clone",null,revision,namespaceId,data);}
    private Map<String,Object> save(String operationId,String kind,String planId,long revision,String namespaceId,Map<String,Object> data){
        ImportWriteBudget budget=ImportWriteBudget.forRequest(c);budget.reserveReceiptAllowance();
        String state=switch(kind){
            case "analysis" -> planId==null?"IMPORT_CONFLICT":"PLAN_READY";
            case "apply" -> "APPLIED";
            default -> "COMMITTED";
        };
        String id=UUID.randomUUID().toString();
        boolean clone=kind.equals("clone");
        String scope=clone?"server":namespaceId;
        var core=new CoreRepository(c);
        var owner=clone?core.find("User","id",c.actorId):core.namespace(namespaceId);
        String unit=c.server.prepareWorkflowCommit(c,scope,owner);
        var root=new Root(id,namespaceId,clone?"server":"namespace",c.actorId,operationId,kind,state,
            revision,planId,Long.MAX_VALUE,c.resultSourcePackage,"{}");
        results.create(root,unit);
        var collections=new TreeMap<String,Object>();
        var metadata=new LinkedHashMap<String,Object>();
        for(var entry:data.entrySet()){
            if(entry.getValue() instanceof Map<?,?> map){
                var saved=saveCollection(id,entry.getKey(),"mapping",map);
                if(!entry.getKey().startsWith("_"))collections.put(entry.getKey(),saved.descriptor());
            }else if(entry.getValue() instanceof Collection<?> values){
                var saved=saveCollection(id,entry.getKey(),"sequence",values);
                if(!entry.getKey().startsWith("_"))collections.put(entry.getKey(),saved.descriptor());
            }else metadata.put(entry.getKey(),entry.getValue());
        }
        if(!metadata.isEmpty())collections.put("metadata",saveCollection(id,"metadata","mapping",metadata).descriptor());
        flushSegments();
        long published=c.server.sessionTime();
        results.complete(id,unit,Math.addExact(published,ImportResultContract.RETENTION),Json.write(collections));
        return leasedEnvelope(results.root(id,unit),published);
    }

    private CollectionRef saveCollection(String resultId,String name,String kind,Object input){
        return saveCollection(resultId,UUID.randomUUID().toString(),name,kind,input);
    }

    private record CollectionRef(String id,Map<String,Object> descriptor) {}

    private CollectionRef saveCollection(String resultId,String id,String name,String kind,Object input) {
        boolean mapping=kind.equals("mapping");
        Collection<?> values=mapping?((Map<?,?>)input).entrySet():(Collection<?>)input;
        createCollection(resultId,id,name,kind);
        long position=0;
        for(Object raw:values) {
            c.state.kernel.assertOpen();
            String key=null;
            Object value=raw;
            if(mapping) {
                var pair=(Map.Entry<?,?>)raw;
                if(!(pair.getKey() instanceof String text))throw new IllegalArgumentException("Import result mapping keys must be strings");
                key=text;
                value=pair.getValue();
            }
            // Reserve space for both tagged descriptors and their enclosing mapping entry.
            Object keyDescriptor=mapping?describe(resultId,key):null;
            Object descriptor=describe(resultId,value);
            Object entry=mapping?Json.map("key",keyDescriptor,"value",descriptor):descriptor;
            if(encodedBytes(entry)>MAX_ENTRY_BYTES)throw new IllegalArgumentException("Import result entry exceeds the bounded segment size");
            int size=mapping?key.getBytes(StandardCharsets.UTF_8).length:-1;
            append(resultId,id,new Entry(position++,Json.write(descriptor),
                mapping?Json.write(keyDescriptor):null,size>=0&&size<=ImportResultContract.TEXT_BYTES?key:null,
                mapping?hash(key):null,size));
        }
        results.completeCollection(resultId,id,position);
        return new CollectionRef(id,Json.map("kind",kind,"count",position));
    }

    private Object describe(String resultId,Object value) {
        String kind=value instanceof Map<?,?>?"mapping":value instanceof Collection<?>?"sequence":null;
        if(kind!=null) {
            String id=UUID.randomUUID().toString();
            var ref=saveCollection(resultId,id,id,kind,value);
            return Json.map("type",kind,"collection",id,"count",ref.descriptor().get("count"));
        }
        var scalar=Json.map("type","scalar","value",value);
        if(value instanceof String text && encodedBytes(scalar)>TEXT_CHUNK_BYTES-64) {
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
            String id=UUID.randomUUID().toString();
            createCollection(resultId,id,id,"sequence");
            long count=0;
            for(int start=0;start<bytes.length;) {
                c.state.kernel.assertOpen();
                // JSON can grow to six bytes per input byte for control characters.
                int end=start+Math.min(bytes.length-start,TEXT_CHUNK_BYTES);
                String chunk;
                while(true) {
                    while(end<bytes.length&&(bytes[end]&0xc0)==0x80)end--;
                    chunk=new String(bytes,start,end-start,StandardCharsets.UTF_8);
                    if(encodedBytes(Json.map("type","scalar","value",chunk))<=MAX_ENTRY_BYTES)break;
                    end=start+(end-start)/2;
                }
                append(resultId,id,new Entry(count++,Json.write(Json.map("type","scalar","value",chunk)),
                    null,null,null,-1));
                start=end;
            }
            results.completeCollection(resultId,id,count);
            return Json.map("type","text","collection",id,"bytes",(long)bytes.length);
        }
        return scalar;
    }

    private static int encodedBytes(Object value) {return Json.write(value).getBytes(StandardCharsets.UTF_8).length;}

    private void createCollection(String result,String id,String name,String kind) {
        results.createCollection(result,id,name,kind);
    }

    private void append(String result,String collection,Entry entry) {
        segments.add(new Segment(result,collection,entry));
        if(segments.size()==64)flushSegments();
    }

    private void flushSegments() {
        if(segments.isEmpty())return;
        results.append(segments);
        segments.clear();
    }

    Map<String,Object> envelope(String resultId) {
        return acquire(requireRoot(resultId,true));
    }

    Map<String,Object> envelopeForPlan(String resultId,String planId,String namespaceId) {
        Root root=requireRoot(resultId,true);
        if(!namespaceId.equals(root.namespace()) || !planId.equals(root.plan()))
            c.fail("RESULT_UNAVAILABLE","Import plan result is unavailable");
        return acquire(root);
    }

    private Map<String,Object> acquire(Root root) {
        long now=c.server.sessionTime();
        if(now>=expiry(root))c.fail("RESULT_EXPIRED","Import result reader admission has expired");
        return leasedEnvelope(root,now);
    }

    void publish(Map<String,Object> envelope) {
        // The action may still have substantial history work after writing its result segments.
        // Start the public admission and initial lease windows at the end of that work.
        String id=(String)envelope.get("resultId"),unit=c.server.operationalUnit(c.state.kernel);
        long now=c.server.sessionTime(), expires=Math.addExact(now,ImportResultContract.RETENTION);
        results.complete(id,unit,expires,null);
        envelope.putAll(leasedEnvelope(results.root(id,unit),now));
        if(envelope.get("planId") instanceof String planId) {
            var plans=c.server.storage.importPlans;
            var plan=plans.plan(planId,unit);
            if(plan!=null)plans.save(plan.transition(plan.state(),plan.lastActivity(),expires),unit);
        }
    }

    private Map<String,Object> leasedEnvelope(Root root,long now) {
        long deadline=Math.addExact(now,ImportResultContract.READER);
        var result=envelope(root);
        result.put("leaseId",CursorCodec.encode(c,leaseBinding(root,deadline,UUID.randomUUID().toString())));
        result.put("leaseExpiresAt",deadline);
        return result;
    }

    private Map<String,Object> leaseBinding(Root root,long deadline,String nonce) {
        return Json.map("type","import-result-lease","resultId",root.id(),"actorId",c.actorId,
            "namespaceId",root.namespace(),"expires",deadline,"nonce",nonce);
    }

    private long expiry(Root root) {return root.expires();}

    private long requireLease(Root root,String leaseId) {
        long now=c.server.sessionTime();
        if(leaseId.length()>2048)c.fail("VALIDATION_FAILED","Invalid import result lease");
        var fields=CursorCodec.decode(c,leaseId,Long.MIN_VALUE);
        if(!(fields.get("expires") instanceof Long deadline) || !(fields.get("nonce") instanceof String nonce)
                || !leaseBinding(root,deadline,nonce).equals(fields) || deadline>Math.addExact(expiry(root),ImportResultContract.READER))
            throw ExceptionHandler.fail("VALIDATION_FAILED","Invalid import result lease");
        checkDeadline(deadline);
        return deadline;
    }

    private void checkDeadline(long deadline) {
        if(c.server.sessionTime()>=deadline)c.fail("RESULT_EXPIRED","Import result reader lease has expired");
    }

    void pinForApply(String resultId) {
        Root root=requireRoot(resultId,false);
        if(c.server.sessionTime()>=expiry(root))c.fail("PLAN_EXPIRED","Import plan input has expired");
    }

    private Map<String,Object> envelope(Root root){var result=Json.map("format",ImportResultContract.FORMAT,"resultId",root.id(),"operationId",root.operation(),"kind",root.kind(),"state",root.state(),"namespaceId",root.namespace(),"planId",root.plan(),"revision",root.revision(),"expiresAt",root.expires(),"collections",Json.read(root.collections()));return result;}

    Map<String,Object> page(Map<String,Object> request) {
        BaseService.fields(request,"resultId","leaseId","collection","cursor","limit");
        String resultId=RequestContext.uuid(request.get("resultId"));
        String leaseId=BaseService.string(request.get("leaseId"));
        String name=BaseService.string(request.get("collection"));
        Root root=requireRoot(resultId,true);
        long deadline=requireLease(root,leaseId);
        ImportResultStore.Collection collection=publicCollection(root,name);
        String kind=collection.kind();
        long count=collection.count();
        long limit=request.get("limit")==null?PAGE_LIMIT:BaseService.integer(request.get("limit"));
        if(limit<1 || limit>PAGE_LIMIT)c.fail("VALIDATION_FAILED","Page limit is outside the supported range");
        long position=0;
        if(request.get("cursor")!=null) {
            var supplied=ValueCodec.map(request.get("cursor"));
            position=BaseService.integer(supplied.get("position"));
            if(position<=0 || position>=count || !cursor(resultId,leaseId,name,position,deadline).equals(supplied))
                c.fail("VALIDATION_FAILED","Invalid result cursor");
        }
        String collectionId=collection.id();
        var entries=new ArrayList<Object>();
        int bytes=0;
        // Read a bounded window in one indexed query. The wire byte limit still governs the page.
        for(var row:results.entries(resultId,collectionId,position,(int)Math.min(limit,count-position))) {
            checkDeadline(deadline);
            if(row.position()!=position)c.fail("RESULT_UNAVAILABLE","Import result segment is missing");
            Object value=Json.read(row.value());
            Object entry=kind.equals("mapping")?Json.map("key",Json.read(row.key()),"value",value):value;
            int size=encodedBytes(entry);
            if(size>MAX_ENTRY_BYTES)c.fail("RESULT_UNAVAILABLE","Import result segment exceeds its bound");
            if(bytes+size>PAGE_BYTES)break;
            bytes+=size;
            entries.add(entry);
            position++;
        }
        if(position<count && entries.isEmpty())c.fail("RESULT_UNAVAILABLE","Import result segment is missing");
        checkDeadline(deadline);
        return Json.map("resultId",resultId,"collection",name,"entries",entries,
            "nextCursor",position<count?cursor(resultId,leaseId,name,position,deadline):null);
    }

    private Map<String,Object> cursor(String id,String lease,String name,long position,long deadline) {
        var fields=Json.map("resultId",id,"leaseId",lease,"collection",name,"position",position);
        String proof=CursorCodec.requestHash(c,Json.map("type","import-result-cursor","actorId",c.actorId,
            "expires",deadline,"cursor",fields));
        fields.put("integrity",proof);
        return fields;
    }

    Map<String,Object> entry(Map<String,Object> request) {
        BaseService.fields(request,"resultId","leaseId","collection","key");
        String resultId=RequestContext.uuid(request.get("resultId"));
        String name=BaseService.string(request.get("collection"));
        Root root=requireRoot(resultId,true);
        long deadline=requireLease(root,BaseService.string(request.get("leaseId")));
        ImportResultStore.Collection collection=publicCollection(root,name);
        if(!"mapping".equals(collection.kind()))c.fail("NOT_FOUND","Import result mapping is unavailable");
        String key=BaseService.string(request.get("key"));
        if(key.getBytes(StandardCharsets.UTF_8).length>ImportResultContract.TEXT_BYTES)
            c.fail("VALIDATION_FAILED","Entry keys must fit the bounded lookup size; use page iteration for larger keys");
        Entry row=lookup(resultId,collection.id(),key);
        checkDeadline(deadline);
        return row==null?Json.map("resultId",resultId,"collection",name,"found",false)
            :Json.map("resultId",resultId,"collection",name,"found",true,"value",Json.read(row.value()));
    }

    private ImportResultStore.Collection publicCollection(Root root,String name) {
        var specs=ValueCodec.map(Json.read(root.collections()));
        Object rawSpec=specs.get(name);
        var collection=results.collection(root.id(),name);
        if(collection==null)
            c.fail(rawSpec==null?"NOT_FOUND":"RESULT_UNAVAILABLE","Import result collection is unavailable");
        if(rawSpec==null && !name.equals(collection.id()))
            c.fail("NOT_FOUND","Import result collection is unavailable");
        if(rawSpec!=null && (!collection.kind().equals(ValueCodec.map(rawSpec).get("kind"))
                || !Long.valueOf(collection.count()).equals(ValueCodec.map(rawSpec).get("count"))))
            c.fail("RESULT_UNAVAILABLE","Import result collection metadata differs");
        return collection;
    }

    Map<String,Object> mappingView(String resultId,String name) {
        if(results.root(resultId,c.server.operationalUnit(c.state.kernel))==null)return Map.of();
        var collection=results.collection(resultId,name);
        if(collection==null)return Map.of();
        return new AbstractMap<>() {
            public Object get(Object key) {
                if(!(key instanceof String text))return null;
                var row=lookup(resultId,collection.id(),text);
                return row==null?null:decodeValue(resultId,row.value());
            }
            public boolean containsKey(Object key) {
                return key instanceof String text && lookup(resultId,collection.id(),text)!=null;
            }
            public int size(){return (int)Math.min(Integer.MAX_VALUE,collection.count());}
            public Set<Map.Entry<String,Object>> entrySet(){
                return new AbstractSet<>() {
                    public int size(){return (int)Math.min(Integer.MAX_VALUE,collection.count());}
                    public Iterator<Map.Entry<String,Object>> iterator(){
                        return iteratorFor(collection.id(),resultId,collection.count());
                    }
                };
            }
        };
    }

    private static final int MAPPING_ITERATOR_BATCH=128;
    private Iterator<Map.Entry<String,Object>> iteratorFor(String collectionId,String resultId,long count){
        return new Iterator<>() {
            private long nextPosition;
            private Iterator<ImportResultStore.Entry> batch=Collections.emptyIterator();
            public boolean hasNext() {
                if(!batch.hasNext() && nextPosition<count){
                    c.state.kernel.assertOpen();
                    batch=results.entries(resultId,collectionId,nextPosition,MAPPING_ITERATOR_BATCH).iterator();
                    if(!batch.hasNext())c.fail("RESOURCE_CORRUPT","Import result mapping segment is missing");
                }
                return batch.hasNext();
            }
            public Map.Entry<String,Object> next() {
                if(!hasNext())throw new NoSuchElementException();
                var row=batch.next();
                if(row.position()!=nextPosition++)c.fail("RESOURCE_CORRUPT","Import result mapping position is not contiguous");
                return new AbstractMap.SimpleImmutableEntry<>(decodeKey(resultId,row),decodeValue(resultId,row.value()));
            }
        };
    }

    private String decodeKey(String resultId,Entry row){
        if(row.mapKey()!=null)return row.mapKey();
        Object raw=Json.read(row.key());
        if(!(raw instanceof Map<?,?> descriptor))throw new IllegalStateException("Invalid import result mapping key");
        if("scalar".equals(descriptor.get("type"))&&descriptor.get("value") instanceof String text)return text;
        if(!"text".equals(descriptor.get("type")))throw new IllegalStateException("Invalid import result mapping key descriptor");
        return decodeText(resultId,(String)descriptor.get("collection"));
    }

    private String decodeText(String resultId,String collectionName){
        var collection=results.collection(resultId,collectionName);
        if(collection==null)throw new IllegalStateException("Missing import result text collection");
        var text=new StringBuilder();
        for(long position=0;position<collection.count();){
            var parts=results.entries(resultId,collection.id(),position,64);
            if(parts.isEmpty())throw new IllegalStateException("Missing import result text segment");
            for(var part:parts){
                c.state.kernel.assertOpen();
                Object value=Json.read(part.value());
                if(part.position()!=position++ || !(value instanceof Map<?,?> chunk) || !"scalar".equals(chunk.get("type"))
                        || !(chunk.get("value") instanceof String segment))
                    throw new IllegalStateException("Invalid import result text segment");
                text.append(segment);
            }
        }
        return text.toString();
    }

    private Entry lookup(String result,String collection,String key) {
        for(var candidate:results.matchingKeys(result,collection,hash(key))) {
            if(candidate.mapKey()!=null?key.equals(candidate.mapKey()):
                    candidate.keyLength()==key.getBytes(StandardCharsets.UTF_8).length && key.equals(decodeKey(result,candidate)))
                return candidate;
        }
        return null;
    }

    private Object decodeValue(String resultId,String encoded){
        Object value=Json.read(encoded);
        if(value instanceof Map<?,?> descriptor){
            if("scalar".equals(descriptor.get("type")))return descriptor.get("value");
            if("text".equals(descriptor.get("type")))return decodeText(resultId,(String)descriptor.get("collection"));
        }
        return value;
    }
    private Root requireRoot(String id,boolean checkAccess) {
        var root=results.root(id,null);
        if(root==null)c.fail("RESULT_UNAVAILABLE","Import result is unavailable");
        // Source authorization takes the package lock before the result pin, as apply does.
        if(checkAccess)authorize(root);
        c.server.lockImportResult(c.state.kernel,id);
        root=results.root(id,null);
        if(root==null)c.fail("RESULT_UNAVAILABLE","Import result is unavailable");
        return root;
    }

    private void authorize(Root root) {
        String namespace=root.namespace();
        var core=new CoreRepository(c.tx,c.timestamp);
        Node user=core.find("User","id",c.actorId);
        if(!c.actorId.equals(root.actor()))
            c.fail("RESULT_UNAVAILABLE","Import result is unavailable");
        if(user==null || !Boolean.TRUE.equals(user.getProperty("enabled",false))
                || core.namespace(namespace)==null)
            c.fail("FORBIDDEN","Import result is unavailable");
        String role=AuthorizationAdapter.role(c,core,namespace);
        if(role==null || BaseService.rank(role)<1)
            c.fail("FORBIDDEN","Import result is unavailable");
        String scope="clone".equals(root.kind())?"server":namespace;
        var receipt=core.receipt(c.actorId,scope,root.operation());
        if(receipt==null || !root.id().equals(receipt.importResultId()))
            c.fail("RESULT_UNAVAILABLE","Import result is unavailable");
        if(root.sourcePackage()!=null)new ExchangePackages(c).authorizeResultSource(root.sourcePackage());
    }

    private static String hash(String key){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
