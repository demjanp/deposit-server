package deposit.service;

import deposit.common.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;

/** Receipt-owned bounded import result collections stored in the enclosing Neo4j transaction. */
final class ImportResultRepository {
    static final int MAX_ENTRY_BYTES=32*1024, TEXT_CHUNK_BYTES=16*1024, PAGE_LIMIT=256, PAGE_BYTES=512*1024;
    private static final String ROOT="DepositImportResult", COLLECTION="DepositImportResultCollection", ENTRY="DepositImportResultEntry";
    private final RequestContext c;
    ImportResultRepository(RequestContext context){c=context;}

    Map<String,Object> save(String operationId,String kind,String planId,long revision,long expiresAt,Map<String,Object> data){return save(operationId,kind,planId,revision,expiresAt,c.namespaceId,data);}
    Map<String,Object> saveClone(String operationId,String namespaceId,long revision,long expiresAt,Map<String,Object> data){return save(operationId,"clone",null,revision,expiresAt,namespaceId,data);}
    private Map<String,Object> save(String operationId,String kind,String planId,long revision,long expiresAt,String namespaceId,Map<String,Object> data){
        ImportWriteBudget budget=ImportWriteBudget.forRequest(c);budget.reserveReceiptAllowance();
        String state=switch(kind){
            case "analysis" -> planId==null?"committed":"PLAN_READY";
            case "apply" -> "APPLIED";
            default -> "committed";
        };
        String id=UUID.randomUUID().toString();var rootProperties=Json.map("id",id,"namespaceId",namespaceId,"scope",kind.equals("clone")?"server":"namespace","actorId",c.actorId,"operationId",operationId,"kind",kind,"state",state,"revision",revision,"expiresAt",expiresAt);if(planId!=null)rootProperties.put("planId",planId);Node root=create(ROOT,rootProperties);
        var collections=new TreeMap<String,Object>();var internal=new TreeMap<String,String>();var metadata=new LinkedHashMap<String,Object>();
        for(var entry:data.entrySet()){
            if(entry.getValue() instanceof Map<?,?> map){CollectionRef saved=saveCollection(id,entry.getKey(),"mapping",map);if(entry.getKey().startsWith("_"))internal.put(entry.getKey(),saved.id);else collections.put(entry.getKey(),saved.descriptor);}
            else if(entry.getValue() instanceof Collection<?> values){CollectionRef saved=saveCollection(id,entry.getKey(),"sequence",values);if(entry.getKey().startsWith("_"))internal.put(entry.getKey(),saved.id);else collections.put(entry.getKey(),saved.descriptor);}
            else metadata.put(entry.getKey(),entry.getValue());
        }
        if(!metadata.isEmpty())collections.put("metadata",saveCollection(id,"metadata","mapping",metadata).descriptor);
        set(root,"collections",Json.write(collections));set(root,"internalCollections",Json.write(internal));return envelope(root);
    }

    private CollectionRef saveCollection(String resultId,String name,String kind,Object input){
        String id=UUID.randomUUID().toString();Collection<?> values=kind.equals("mapping")?((Map<?,?>)input).entrySet(): (Collection<?>)input;Node collection=create(COLLECTION,Json.map("resultId",resultId,"id",id,"name",name,"kind",kind,"count",0L));
        long position=0;
        for(Object raw:values){String mapKey=null;Object keyDescriptor=null,value=raw;if(kind.equals("mapping")){var entry=(Map.Entry<?,?>)raw;Object key=entry.getKey();if(!(key instanceof String))throw new IllegalArgumentException("Import result mapping keys must be strings");mapKey=(String)key;keyDescriptor=describe(resultId,mapKey);value=entry.getValue();}
            Object descriptor=describe(resultId,value);String encoded=Json.write(descriptor);if(encoded.getBytes(StandardCharsets.UTF_8).length>MAX_ENTRY_BYTES)throw new IllegalArgumentException("Import result entry exceeds the bounded segment size");var properties=Json.map("resultId",resultId,"collectionId",id,"position",position++,"value",encoded);if(mapKey!=null){properties.put("key",Json.write(keyDescriptor));properties.put("mapKeyHash",hash(mapKey));if(mapKey.getBytes(StandardCharsets.UTF_8).length<=MAX_ENTRY_BYTES/2)properties.put("mapKey",mapKey);properties.put("mapKeyLength",mapKey.getBytes(StandardCharsets.UTF_8).length);}create(ENTRY,properties);
        }
        set(collection,"count",position);return new CollectionRef(id,Json.map("kind",kind,"count",position));
    }
    private record CollectionRef(String id,Map<String,Object> descriptor){}

    private Object describe(String resultId,Object value){
        if(value instanceof Map<?,?> map){String collection=saveCollectionWithId(resultId,UUID.randomUUID().toString(),"mapping",map);return Json.map("type","mapping","collection",collection,"count",collectionCount(resultId,collection));}
        if(value instanceof Collection<?> values){String collection=saveCollectionWithId(resultId,UUID.randomUUID().toString(),"sequence",values);return Json.map("type","sequence","collection",collection,"count",collectionCount(resultId,collection));}
        if(value instanceof String text){byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_ENTRY_BYTES/2){String collection=UUID.randomUUID().toString();Node col=create(COLLECTION,Json.map("resultId",resultId,"id",collection,"name",collection,"kind","sequence","count",0L));long count=0;for(int start=0;start<bytes.length;){int end=Math.min(bytes.length,start+TEXT_CHUNK_BYTES);while(end<bytes.length&&(bytes[end]&0xc0)==0x80)end--;if(end==start)throw new IllegalArgumentException("Cannot segment UTF-8 text");String chunk=new String(bytes,start,end-start,StandardCharsets.UTF_8);create(ENTRY,Json.map("resultId",resultId,"collectionId",collection,"position",count++,"value",Json.write(Json.map("type","scalar","value",chunk))));start=end;}set(col,"count",count);return Json.map("type","text","collection",collection,"bytes",(long)bytes.length);}}
        return Json.map("type","scalar","value",value);
    }

    private String saveCollectionWithId(String resultId,String id,String kind,Object input){
        Collection<?> values=kind.equals("mapping")?((Map<?,?>)input).entrySet():(Collection<?>)input;Node collection=create(COLLECTION,Json.map("resultId",resultId,"id",id,"name",id,"kind",kind,"count",0L));long position=0;
        for(Object raw:values){String mapKey=null;Object value=raw,keyDescriptor=null;if(kind.equals("mapping")){var entry=(Map.Entry<?,?>)raw;if(!(entry.getKey() instanceof String key))throw new IllegalArgumentException("Import result mapping keys must be strings");mapKey=key;keyDescriptor=describe(resultId,key);value=entry.getValue();}String encoded=Json.write(describe(resultId,value));if(encoded.getBytes(StandardCharsets.UTF_8).length>MAX_ENTRY_BYTES)throw new IllegalArgumentException("Import result entry exceeds the bounded segment size");var properties=Json.map("resultId",resultId,"collectionId",id,"position",position++,"value",encoded);if(mapKey!=null){properties.put("key",Json.write(keyDescriptor));properties.put("mapKeyHash",hash(mapKey));properties.put("mapKeyLength",mapKey.getBytes(StandardCharsets.UTF_8).length);if(mapKey.getBytes(StandardCharsets.UTF_8).length<=MAX_ENTRY_BYTES/2)properties.put("mapKey",mapKey);}create(ENTRY,properties);}
        set(collection,"count",position);return id;
    }

    private long collectionCount(String resultId,String id){Node collection=find(COLLECTION,Map.of("resultId",resultId,"id",id));return ((Number)collection.getProperty("count")).longValue();}

    private Node create(String label,Map<String,Object> properties){ImportWriteBudget.forRequest(c).reserveRecord(properties);Node node=c.tx.createNode(Label.label(label));properties.forEach((key,value)->{if(value!=null)node.setProperty(key,value);});return node;}
    private void set(Node node,String key,Object value){ImportWriteBudget.forRequest(c).reserveRecord(Json.map(key,value));node.setProperty(key,value);}

    Map<String,Object> envelope(String resultId){Node root=find(ROOT,Map.of("id",resultId));if(root==null)c.fail("NOT_FOUND","Import result is unavailable");authorize(root);return envelope(root);}
    Map<String,Object> cloneEnvelope(String resultId){Node root=find(ROOT,Map.of("id",resultId));if(root==null||!"clone".equals(root.getProperty("kind")))c.fail("NOT_FOUND","Clone result is unavailable");authorize(root);return envelope(root);}
    Map<String,Object> envelopeForPlan(String resultId,String planId,String namespaceId){Node root=find(ROOT,Map.of("id",resultId));if(root==null||!namespaceId.equals(root.getProperty("namespaceId"))||!planId.equals(root.getProperty("planId",null)))c.fail("NOT_FOUND","Import result is unavailable");Node receipt=find("DepositReceipt",Map.of("actorId",root.getProperty("actorId"),"scope",namespaceId,"operationId",root.getProperty("operationId")));if(receipt==null||!resultId.equals(receipt.getProperty("importResultId",null)))c.fail("NOT_FOUND","Import result is unavailable");return envelope(root);}
    Map<String,Object> envelope(String resultId,String state){var value=new LinkedHashMap<>(envelope(resultId));value.put("state",state);return value;}
    Map<String,Object> envelope(String resultId,String state,long expiresAt){var value=new LinkedHashMap<>(envelope(resultId,state));value.put("expiresAt",expiresAt);return value;}
    private Map<String,Object> envelope(Node root){var result=Json.map("format",ImportResultContract.FORMAT,"resultId",root.getProperty("id"),"operationId",root.getProperty("operationId"),"kind",root.getProperty("kind"),"state",root.getProperty("state"),"namespaceId",root.getProperty("namespaceId"),"planId",root.getProperty("planId",null),"revision",root.getProperty("revision"),"expiresAt",root.getProperty("expiresAt"),"collections",Json.read((String)root.getProperty("collections")));return result;}

        Map<String,Object> page(Map<String,Object> request){
        BaseService.fields(request,"resultId","collection","cursor","limit");String resultId=RequestContext.uuid(request.get("resultId")),name=BaseService.string(request.get("collection"));Node root=requireRoot(resultId);authorize(root);Map<String,Object> specs=ValueCodec.map(Json.read((String)root.getProperty("collections")));Object rawSpec=specs.get(name);Node collection=find(COLLECTION,Map.of("resultId",resultId,"name",name));if(collection==null||rawSpec==null&&!name.equals(collection.getProperty("id")))c.fail("NOT_FOUND","Import result collection is unavailable");String kind=(String)collection.getProperty("kind");long count=((Number)collection.getProperty("count")).longValue();if(rawSpec!=null&&(!kind.equals(ValueCodec.map(rawSpec).get("kind"))||count!=((Number)ValueCodec.map(rawSpec).get("count")).longValue()))c.fail("RESOURCE_CORRUPT","Import result collection metadata differs");long start=0;Object rawLimit=request.get("limit");long limit=rawLimit==null?PAGE_LIMIT:BaseService.integer(rawLimit);if(limit<1||limit>PAGE_LIMIT)c.fail("VALIDATION_FAILED","Page limit is outside the supported range");Object rawCursor=request.get("cursor");
        if(rawCursor!=null){
            if(!(rawCursor instanceof Map<?,?>))c.fail("VALIDATION_FAILED","Import result cursor must be a structured Map");Map<String,Object> cursor=ValueCodec.map(rawCursor);BaseService.fields(cursor,"resultId","collection","position","integrity");Map<String,Object> signed=CursorCodec.decode(c,BaseService.string(cursor.get("integrity")));BaseService.fields(signed,"resultId","collection","position","expires");if(!resultId.equals(signed.get("resultId"))||!name.equals(signed.get("collection"))||!resultId.equals(cursor.get("resultId"))||!name.equals(cursor.get("collection")))c.fail("VALIDATION_FAILED","Cursor belongs to a different result collection");Object position=signed.get("position");if(!(position instanceof Long)||((Long)position)<0||!position.equals(cursor.get("position")))c.fail("VALIDATION_FAILED","Invalid result cursor");start=((Long)position).longValue();
        }
        if(start>count)c.fail("VALIDATION_FAILED","Invalid result cursor");String collectionId=(String)collection.getProperty("id");var rows=new ArrayList<Object>();long bytes=0,position=start;while(position<count&&rows.size()<limit){Node row=find(ENTRY,Map.of("resultId",resultId,"collectionId",collectionId,"position",position));if(row==null)c.fail("RESOURCE_CORRUPT","Import result segment is missing");String value=(String)row.getProperty("value");long size=value.getBytes(StandardCharsets.UTF_8).length;if(!rows.isEmpty()&&bytes+size>PAGE_BYTES)break;bytes+=size;Object descriptor=Json.read(value);if(kind.equals("mapping"))rows.add(Json.map("key",Json.read((String)row.getProperty("key")),"value",descriptor));else rows.add(descriptor);position++;}
        Map<String,Object> next=position<count?Json.map("resultId",resultId,"collection",name,"position",position,"integrity",CursorCodec.encode(c,Json.map("resultId",resultId,"collection",name,"position",position,"expires",System.currentTimeMillis()+86_400_000L))):null;return Json.map("resultId",resultId,"collection",name,"entries",rows,"nextCursor",next);
    }

    Map<String,Object> entry(Map<String,Object> request){
        BaseService.fields(request,"resultId","collection","key");String resultId=RequestContext.uuid(request.get("resultId")),name=BaseService.string(request.get("collection"));
        Node root=requireRoot(resultId);authorize(root);Map<String,Object> specs=ValueCodec.map(Json.read((String)root.getProperty("collections")));Object rawSpec=specs.get(name);
        Node collection=find(COLLECTION,Map.of("resultId",resultId,"name",name));
        if(collection==null||rawSpec!=null&& !"mapping".equals(ValueCodec.map(rawSpec).get("kind"))||rawSpec==null&& !"mapping".equals(collection.getProperty("kind")))c.fail("NOT_FOUND","Import result mapping is unavailable");
        String key=BaseService.string(request.get("key"));if(key.getBytes(StandardCharsets.UTF_8).length>MAX_ENTRY_BYTES/2)c.fail("VALIDATION_FAILED","Entry keys must fit the bounded lookup size; use page iteration for larger keys");
        Node row=lookup(collection,key);return row==null?Json.map("resultId",resultId,"collection",name,"found",false):Json.map("resultId",resultId,"collection",name,"found",true,"value",Json.read((String)row.getProperty("value")));
    }

    Map<String,Object> clonePage(Map<String,Object> request){String resultId=RequestContext.uuid(request.get("resultId"));Node root=requireRoot(resultId);if(!"clone".equals(root.getProperty("kind")))c.fail("NOT_FOUND","Clone result is unavailable");return page(request);}
    Map<String,Object> cloneEntry(Map<String,Object> request){String resultId=RequestContext.uuid(request.get("resultId"));Node root=requireRoot(resultId);if(!"clone".equals(root.getProperty("kind")))c.fail("NOT_FOUND","Clone result is unavailable");return entry(request);}

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
    private Node requireRoot(String id){Node root=find(ROOT,Map.of("id",id));if(root==null)c.fail("NOT_FOUND","Import result is unavailable");return root;}
    private void authorize(Node root){boolean clone="clone".equals(root.getProperty("kind"));String receiptScope=clone?"server":c.namespaceId;String resultScope=clone?"server":"namespace";if(!resultScope.equals(root.getProperty("scope",resultScope))||(!clone&&!c.namespaceId.equals(root.getProperty("namespaceId")))||!c.actorId.equals(root.getProperty("actorId")))c.fail("NOT_FOUND","Import result is unavailable");String operation=(String)root.getProperty("operationId");Node receipt=find("DepositReceipt",Map.of("actorId",c.actorId,"scope",receiptScope,"operationId",operation));if(receipt==null||!c.actorId.equals(receipt.getProperty("actorId"))||!root.getProperty("id").equals(receipt.getProperty("importResultId",null)))c.fail("NOT_FOUND","Import result is unavailable");}
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
