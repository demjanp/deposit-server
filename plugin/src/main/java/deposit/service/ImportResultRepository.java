package deposit.service;

import deposit.common.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.neo4j.graphdb.Label;
import deposit.repository.CoreRepository;
import org.neo4j.graphdb.Node;

/** Temporary bounded import result collections stored in the enclosing Neo4j transaction. */
final class ImportResultRepository {
    static final int MAX_ENTRY_BYTES=32*1024, TEXT_CHUNK_BYTES=16*1024, PAGE_LIMIT=256, PAGE_BYTES=512*1024;
    private static final String ROOT=ImportResultContract.LABEL, COLLECTION="DepositImportResultCollection", ENTRY="DepositImportResultEntry";
    private final RequestContext c;
    ImportResultRepository(RequestContext context){c=context;}

    Map<String,Object> save(String operationId,String kind,String planId,long revision,Map<String,Object> data){return save(operationId,kind,planId,revision,c.namespaceId,data);}
    Map<String,Object> saveClone(String operationId,String namespaceId,long revision,Map<String,Object> data){return save(operationId,"clone",null,revision,namespaceId,data);}
    private Map<String,Object> save(String operationId,String kind,String planId,long revision,String namespaceId,Map<String,Object> data){
        ImportWriteBudget budget=ImportWriteBudget.forRequest(c);budget.reserveReceiptAllowance();
        String state=switch(kind){
            case "analysis" -> planId==null?"IMPORT_CONFLICT":"PLAN_READY";
            case "apply" -> "APPLIED";
            default -> "COMMITTED";
        };
        String id=UUID.randomUUID().toString();var rootProperties=Json.map("id",id,"namespaceId",namespaceId,"scope",kind.equals("clone")?"server":"namespace","actorId",c.actorId,"operationId",operationId,"kind",kind,"state",state,"revision",revision);if(planId!=null)rootProperties.put("planId",planId);Node root=create(ROOT,rootProperties);
        var collections=new TreeMap<String,Object>();var internal=new TreeMap<String,String>();var metadata=new LinkedHashMap<String,Object>();
        for(var entry:data.entrySet()){
            if(entry.getValue() instanceof Map<?,?> map){CollectionRef saved=saveCollection(id,entry.getKey(),"mapping",map);if(entry.getKey().startsWith("_"))internal.put(entry.getKey(),saved.id);else collections.put(entry.getKey(),saved.descriptor);}
            else if(entry.getValue() instanceof Collection<?> values){CollectionRef saved=saveCollection(id,entry.getKey(),"sequence",values);if(entry.getKey().startsWith("_"))internal.put(entry.getKey(),saved.id);else collections.put(entry.getKey(),saved.descriptor);}
            else metadata.put(entry.getKey(),entry.getValue());
        }
        if(!metadata.isEmpty())collections.put("metadata",saveCollection(id,"metadata","mapping",metadata).descriptor);
        set(root,"collections",Json.write(collections));set(root,"internalCollections",Json.write(internal));
        long published=c.server.sessionTime();
        set(root,"expiresAt",Math.addExact(published,ImportResultContract.RETENTION));
        if(c.resultSourcePackage!=null)set(root,"sourcePackageId",c.resultSourcePackage);
        return leasedEnvelope(root,published);
    }

    private CollectionRef saveCollection(String resultId,String name,String kind,Object input){
        return saveCollection(resultId,UUID.randomUUID().toString(),name,kind,input);
    }

    private record CollectionRef(String id,Map<String,Object> descriptor) {}

    private CollectionRef saveCollection(String resultId,String id,String name,String kind,Object input) {
        boolean mapping=kind.equals("mapping");
        Collection<?> values=mapping?((Map<?,?>)input).entrySet():(Collection<?>)input;
        Node collection=create(COLLECTION,Json.map("resultId",resultId,"id",id,"name",name,"kind",kind,"count",0L));
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
            var properties=Json.map("resultId",resultId,"collectionId",id,"position",position++,"value",Json.write(descriptor));
            if(mapping) {
                int size=key.getBytes(StandardCharsets.UTF_8).length;
                properties.put("key",Json.write(keyDescriptor));
                properties.put("mapKeyHash",hash(key));
                properties.put("mapKeyLength",size);
                if(size<=ImportResultContract.TEXT_BYTES)properties.put("mapKey",key);
            }
            create(ENTRY,properties);
        }
        set(collection,"count",position);
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
            Node collection=create(COLLECTION,Json.map("resultId",resultId,"id",id,"name",id,"kind","sequence","count",0L));
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
                create(ENTRY,Json.map("resultId",resultId,"collectionId",id,"position",count++,
                    "value",Json.write(Json.map("type","scalar","value",chunk))));
                start=end;
            }
            set(collection,"count",count);
            return Json.map("type","text","collection",id,"bytes",(long)bytes.length);
        }
        return scalar;
    }

    private static int encodedBytes(Object value) {return Json.write(value).getBytes(StandardCharsets.UTF_8).length;}

    private Node create(String label,Map<String,Object> properties){ImportWriteBudget.forRequest(c).reserveRecord(properties);Node node=c.tx.createNode(Label.label(label));properties.forEach((key,value)->{if(value!=null)node.setProperty(key,value);});return node;}
    private void set(Node node,String key,Object value){ImportWriteBudget.forRequest(c).reserveRecord(Json.map(key,value));node.setProperty(key,value);}

    Map<String,Object> envelope(String resultId) {
        return acquire(requireRoot(resultId));
    }

    Map<String,Object> envelopeForPlan(String resultId,String planId,String namespaceId) {
        Node root=requireRoot(resultId);
        if(!namespaceId.equals(root.getProperty("namespaceId")) || !planId.equals(root.getProperty("planId",null)))
            c.fail("RESULT_UNAVAILABLE","Import plan result is unavailable");
        return acquire(root);
    }

    private Map<String,Object> acquire(Node root) {
        authorize(root);
        long now=c.server.sessionTime();
        if(now>=expiry(root))c.fail("RESULT_EXPIRED","Import result reader admission has expired");
        return leasedEnvelope(root,now);
    }

    void publish(Map<String,Object> envelope) {
        // The action may still have substantial history work after writing its result segments.
        // Start the public admission and initial lease windows at the end of that work.
        Node root=find(ROOT,Map.of("id",envelope.get("resultId")));
        long now=c.server.sessionTime(), expires=Math.addExact(now,ImportResultContract.RETENTION);
        set(root,"expiresAt",expires);
        envelope.putAll(leasedEnvelope(root,now));
        if(envelope.get("planId") instanceof String planId) {
            Node plan=new CoreRepository(c.tx,c.timestamp).find("DepositImportPlan","id",planId);
            if(plan!=null)set(plan,"expiresAt",expires);
        }
    }

    private Map<String,Object> leasedEnvelope(Node root,long now) {
        long deadline=Math.addExact(now,ImportResultContract.READER);
        var result=envelope(root);
        result.put("leaseId",CursorCodec.encode(c,leaseBinding(root,deadline,UUID.randomUUID().toString())));
        result.put("leaseExpiresAt",deadline);
        return result;
    }

    private Map<String,Object> leaseBinding(Node root,long deadline,String nonce) {
        return Json.map("type","import-result-lease","resultId",root.getProperty("id"),"actorId",c.actorId,
            "namespaceId",root.getProperty("namespaceId"),"expires",deadline,"nonce",nonce);
    }

    private long expiry(Node root) {return (long)root.getProperty("expiresAt");}

    private long requireLease(Node root,String leaseId) {
        authorize(root);
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
        Node root=requireRoot(resultId);
        if(c.server.sessionTime()>=expiry(root))c.fail("PLAN_EXPIRED","Import plan input has expired");
    }

    private Map<String,Object> envelope(Node root){var result=Json.map("format",ImportResultContract.FORMAT,"resultId",root.getProperty("id"),"operationId",root.getProperty("operationId"),"kind",root.getProperty("kind"),"state",root.getProperty("state"),"namespaceId",root.getProperty("namespaceId"),"planId",root.getProperty("planId",null),"revision",root.getProperty("revision"),"expiresAt",root.getProperty("expiresAt"),"collections",Json.read((String)root.getProperty("collections")));return result;}

    Map<String,Object> page(Map<String,Object> request) {
        BaseService.fields(request,"resultId","leaseId","collection","cursor","limit");
        String resultId=RequestContext.uuid(request.get("resultId"));
        String leaseId=BaseService.string(request.get("leaseId"));
        String name=BaseService.string(request.get("collection"));
        Node root=requireRoot(resultId);
        long deadline=requireLease(root,leaseId);
        Node collection=publicCollection(root,name);
        String kind=(String)collection.getProperty("kind");
        long count=(long)collection.getProperty("count");
        long limit=request.get("limit")==null?PAGE_LIMIT:BaseService.integer(request.get("limit"));
        if(limit<1 || limit>PAGE_LIMIT)c.fail("VALIDATION_FAILED","Page limit is outside the supported range");
        long position=0;
        if(request.get("cursor")!=null) {
            var supplied=ValueCodec.map(request.get("cursor"));
            position=BaseService.integer(supplied.get("position"));
            if(position<=0 || position>=count || !cursor(resultId,leaseId,name,position,deadline).equals(supplied))
                c.fail("VALIDATION_FAILED","Invalid result cursor");
        }
        String collectionId=(String)collection.getProperty("id");
        var entries=new ArrayList<Object>();
        int bytes=0;
        while(position<count && entries.size()<limit) {
            checkDeadline(deadline);
            Node row=find(ENTRY,Map.of("resultId",resultId,"collectionId",collectionId,"position",position));
            if(row==null)c.fail("RESULT_UNAVAILABLE","Import result segment is missing");
            Object value=Json.read((String)row.getProperty("value"));
            Object entry=kind.equals("mapping")?Json.map("key",Json.read((String)row.getProperty("key")),"value",value):value;
            int size=encodedBytes(entry);
            if(size>MAX_ENTRY_BYTES)c.fail("RESULT_UNAVAILABLE","Import result segment exceeds its bound");
            if(bytes+size>PAGE_BYTES)break;
            bytes+=size;
            entries.add(entry);
            position++;
        }
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
        Node root=requireRoot(resultId);
        long deadline=requireLease(root,BaseService.string(request.get("leaseId")));
        Node collection=publicCollection(root,name);
        if(!"mapping".equals(collection.getProperty("kind")))c.fail("NOT_FOUND","Import result mapping is unavailable");
        String key=BaseService.string(request.get("key"));
        if(key.getBytes(StandardCharsets.UTF_8).length>ImportResultContract.TEXT_BYTES)
            c.fail("VALIDATION_FAILED","Entry keys must fit the bounded lookup size; use page iteration for larger keys");
        Node row=lookup(collection,key);
        checkDeadline(deadline);
        return row==null?Json.map("resultId",resultId,"collection",name,"found",false)
            :Json.map("resultId",resultId,"collection",name,"found",true,"value",Json.read((String)row.getProperty("value")));
    }

    private Node publicCollection(Node root,String name) {
        var specs=ValueCodec.map(Json.read((String)root.getProperty("collections")));
        Object rawSpec=specs.get(name);
        Node collection=find(COLLECTION,Map.of("resultId",root.getProperty("id"),"name",name));
        if(collection==null)
            c.fail(rawSpec==null?"NOT_FOUND":"RESULT_UNAVAILABLE","Import result collection is unavailable");
        if(rawSpec==null && !name.equals(collection.getProperty("id")))
            c.fail("NOT_FOUND","Import result collection is unavailable");
        if(rawSpec!=null && (!collection.getProperty("kind").equals(ValueCodec.map(rawSpec).get("kind"))
                || !collection.getProperty("count").equals(ValueCodec.map(rawSpec).get("count"))))
            c.fail("RESULT_UNAVAILABLE","Import result collection metadata differs");
        return collection;
    }

    Map<String,Object> mappingView(String resultId,String name){Node collection=find(COLLECTION,Map.of("resultId",resultId,"name",name));if(collection==null){Node root=find(ROOT,Map.of("id",resultId));if(root!=null&&root.hasProperty("internalCollections")){Object id=ValueCodec.map(Json.read((String)root.getProperty("internalCollections"))).get(name);if(id instanceof String collectionId)collection=find(COLLECTION,Map.of("resultId",resultId,"id",collectionId));}}if(collection==null)return Map.of();String collectionId=(String)collection.getProperty("id");long count=((Number)collection.getProperty("count")).longValue();return new AbstractMap<>(){
        public Object get(Object key){if(!(key instanceof String text))return null;Node row=lookup(collectionId,resultId,text);return row==null?null:decodeValue((String)row.getProperty("value"));}
        public boolean containsKey(Object key){return key instanceof String text&&lookup(collectionId,resultId,text)!=null;}
        public int size(){return (int)Math.min(Integer.MAX_VALUE,count);}
        public Set<Entry<String,Object>> entrySet(){return new AbstractSet<>(){public int size(){return ImportResultRepository.this.size(count);}public Iterator<Entry<String,Object>> iterator(){return iteratorFor(collectionId,resultId,count);}};}
    };}
    private static final int MAPPING_ITERATOR_BATCH=128;
    private Iterator<Map.Entry<String,Object>> iteratorFor(String collectionId,String resultId,long count){
        return new Iterator<>(){
            private long nextPosition;private List<Node> batch=List.of();private int batchIndex;private boolean exhausted;
            private void fetch(){if(batchIndex<batch.size()||exhausted)return;c.state.kernel.assertOpen();var rows=c.tx.execute("MATCH (row:"+ENTRY+" {resultId:$resultId,collectionId:$collectionId}) WHERE row.position >= $position RETURN row ORDER BY row.position LIMIT $limit",Map.of("resultId",resultId,"collectionId",collectionId,"position",nextPosition,"limit",MAPPING_ITERATOR_BATCH));var found=new ArrayList<Node>(MAPPING_ITERATOR_BATCH);try{while(rows.hasNext())found.add((Node)rows.next().get("row"));}finally{rows.close();}batch=found;batchIndex=0;if(batch.isEmpty()){exhausted=true;if(nextPosition<count)c.fail("RESOURCE_CORRUPT","Import result mapping segment is missing");}}
            public boolean hasNext(){fetch();return !exhausted;}
            public Map.Entry<String,Object> next(){if(!hasNext())throw new NoSuchElementException();Node row=batch.get(batchIndex++);long position=((Number)row.getProperty("position")).longValue();if(position!=nextPosition)c.fail("RESOURCE_CORRUPT","Import result mapping position is not contiguous");nextPosition=Math.addExact(nextPosition,1L);return new AbstractMap.SimpleImmutableEntry<>(decodeKey(row),decodeValue((String)row.getProperty("value")));}
        };
    }
    private String decodeKey(Node row){if(row.hasProperty("mapKey"))return (String)row.getProperty("mapKey");Object raw=Json.read((String)row.getProperty("key"));if(!(raw instanceof Map<?,?> descriptor))throw new IllegalStateException("Invalid import result mapping key");if("scalar".equals(descriptor.get("type"))&&descriptor.get("value") instanceof String text)return text;if(!"text".equals(descriptor.get("type")))throw new IllegalStateException("Invalid import result mapping key descriptor");String resultId=(String)row.getProperty("resultId"),collection=collectionIdFor(resultId,(String)descriptor.get("collection"));var parts=nodes(ENTRY,Map.of("resultId",resultId,"collectionId",collection));parts.sort(Comparator.comparingLong(n->((Number)n.getProperty("position")).longValue()));var text=new StringBuilder();for(Node part:parts){Object value=Json.read((String)part.getProperty("value"));if(!(value instanceof Map<?,?> chunk)||!"scalar".equals(chunk.get("type"))||!(chunk.get("value") instanceof String segment))throw new IllegalStateException("Invalid import result text segment");text.append(segment);}return text.toString();}
    private int size(long n){return (int)Math.min(Integer.MAX_VALUE,n);}
    private Node lookup(Node collection,String key){return lookup((String)collection.getProperty("id"),(String)collection.getProperty("resultId"),key);}
    private Node lookup(String collectionId,String resultId,String key){String keyHash=hash(key);for(Node candidate:nodes(ENTRY,Map.of("resultId",resultId,"collectionId",collectionId,"mapKeyHash",keyHash)))if(keyEquals(candidate,key))return candidate;return null;}
    private boolean keyEquals(Node row,String expected){if(row.hasProperty("mapKey"))return expected.equals(row.getProperty("mapKey"));if(((Number)row.getProperty("mapKeyLength",-1)).longValue()!=expected.getBytes(StandardCharsets.UTF_8).length)return false;Object raw=Json.read((String)row.getProperty("key"));if(!(raw instanceof Map<?,?> descriptor)||!"text".equals(descriptor.get("type")))return false;String textCollection=(String)descriptor.get("collection"),resultId=(String)row.getProperty("resultId");var parts=nodes(ENTRY,Map.of("resultId",resultId,"collectionId",collectionIdFor(resultId,textCollection)));parts.sort(Comparator.comparingLong(n->((Number)n.getProperty("position")).longValue()));byte[] wanted=expected.getBytes(StandardCharsets.UTF_8);int offset=0;for(Node part:parts){Object chunkDescriptor=Json.read((String)part.getProperty("value"));if(!(chunkDescriptor instanceof Map<?,?> chunk)||!"scalar".equals(chunk.get("type"))||!(chunk.get("value") instanceof String value))return false;byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(offset+bytes.length>wanted.length)return false;for(int i=0;i<bytes.length;i++)if(bytes[i]!=wanted[offset+i])return false;offset+=bytes.length;}return offset==wanted.length;}
    private String collectionIdFor(String resultId,String collectionName){Node collection=find(COLLECTION,Map.of("resultId",resultId,"name",collectionName));return collection==null?collectionName:(String)collection.getProperty("id");}
    private Object decodeValue(String encoded){Object value=Json.read(encoded);if(value instanceof Map<?,?> map&&"scalar".equals(map.get("type")))return map.get("value");return value;}
    private Node requireRoot(String id) {
        Node root=find(ROOT,Map.of("id",id));
        if(root==null)c.fail("RESULT_UNAVAILABLE","Import result is unavailable");
        c.tx.acquireReadLock(root);
        if(find(ROOT,Map.of("id",id))==null)c.fail("RESULT_UNAVAILABLE","Import result is unavailable");
        return root;
    }

    private void authorize(Node root) {
        String namespace=(String)root.getProperty("namespaceId");
        var core=new CoreRepository(c.tx,c.timestamp);
        Node user=core.find("User","id",c.actorId);
        if(!c.actorId.equals(root.getProperty("actorId")))
            c.fail("RESULT_UNAVAILABLE","Import result is unavailable");
        if(user==null || !Boolean.TRUE.equals(user.getProperty("enabled",false))
                || core.namespace(namespace)==null)
            c.fail("FORBIDDEN","Import result is unavailable");
        String role=AuthorizationAdapter.role(c,core,namespace);
        if(role==null || BaseService.rank(role)<1)
            c.fail("FORBIDDEN","Import result is unavailable");
        String scope="clone".equals(root.getProperty("kind"))?"server":namespace;
        Node receipt=core.receipt(c.actorId,scope,(String)root.getProperty("operationId"));
        if(receipt==null || !root.getProperty("id").equals(receipt.getProperty("importResultId",null)))
            c.fail("RESULT_UNAVAILABLE","Import result is unavailable");
        if(root.getProperty("sourcePackageId",null) instanceof String id)new ExchangePackages(c).authorizeResultSource(id);
    }

    private Node find(String label,Map<String,Object> props){
        // Multi-property findNodes bypasses Neo4j's composite index on this
        // result-scale path. Querying the complete property signature retains
        // exact scope and membership without a label scan plus property filter.
        if(!Set.of("DepositImportResultEntry", "DepositImportResultCollection", "DepositImportResult", "DepositReceipt").contains(label))throw new IllegalArgumentException("Unindexed lookup kind");


        var text=new StringBuilder("MATCH (n:").append(label).append(") WHERE ");
        var values=new LinkedHashMap<String,Object>();
        int i=0;for(var entry:props.entrySet()){String prop=entry.getKey();String key="v"+i++;if(i>1)text.append(" AND ");text.append("n.").append(prop).append(" = $").append(key);values.put(key,entry.getValue());}
        text.append(" RETURN n LIMIT 1");try(var found=c.tx.execute(text.toString(),values)){return found.hasNext()?(Node)found.next().get("n"):null;}
    }
    private List<Node> nodes(String label,Map<String,Object> props){var result=new ArrayList<Node>();try(var found=c.tx.findNodes(Label.label(label),props)){while(found.hasNext())result.add(found.next());}return result;}
    private static String hash(String key){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
