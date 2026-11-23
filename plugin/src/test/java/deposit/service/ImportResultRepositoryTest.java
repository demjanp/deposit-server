package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.*;
import deposit.common.Results.Outcome;
import deposit.repository.CoreRepository;
import deposit.repository.SchemaRepository;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.dbms.api.*;
import org.neo4j.graphdb.*;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

class ImportResultRepositoryTest {
    @TempDir Path temporary;
    DatabaseManagementService management;
    GraphDatabaseAPI database;
    ServerContext server;
    StorageManager storage;
    String previousConfig;
    final String actor=UUID.randomUUID().toString(),namespace=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();
    String resultId,largeKey;

    @BeforeEach void setup()throws Exception {
        previousConfig=System.getProperty("deposit.config");Path resources=temporary.resolve("resources"),exports=temporary.resolve("exports");java.nio.file.Files.createDirectories(resources);java.nio.file.Files.createDirectories(exports);Path config=temporary.resolve("deposit-test.properties");var properties=new java.util.Properties();properties.setProperty("resourceRoot",resources.toString());properties.setProperty("exportRoot",exports.toString());try(var out=java.nio.file.Files.newOutputStream(config)){properties.store(out,"test");}System.setProperty("deposit.config",config.toString());
        management=new DatabaseManagementServiceBuilderImplementation(temporary.resolve("db"),type->type!=DepositExtension.class).build();
        database=(GraphDatabaseAPI)management.database("neo4j");server=ServerContext.attach(database.databaseId().databaseId().uuid());var databaseField=ServerContext.class.getDeclaredField("database");databaseField.setAccessible(true);databaseField.set(server,database);storage=new StorageManager(server,database);server.storage=storage;var ready=ServerContext.class.getDeclaredMethod("markReadyForTesting");ready.setAccessible(true);ready.invoke(server);
        largeKey="🔐".repeat(5_000);
        SchemaRepository.initialize(database);
        try(var tx=database.beginTx()){
            createNamespace(tx,namespace);
            var core=new CoreRepository(tx,java.time.ZonedDateTime.now());
            core.createRestored("User",null,actor,Json.map("enabled",true,"serverRole","user"),actor);
            core.setRole(actor,namespace,"owner");
            var context=context(tx,actor);var repo=new ImportResultRepository(context);
            String longText="λ🙂".repeat(12_000);
            var admitted=new LinkedHashMap<String,Object>();admitted.put("source-1",null);for(int i=0;i<300;i++)admitted.put("source-object-"+i,UUID.randomUUID().toString());
            Map<String,Object> data=Json.map("objectMappings",admitted,"nested",Json.map(largeKey,"large-key-value","text",longText),"groups",List.of(Json.map("name","one"),Json.map("name","two"),Json.map("name","three")));
            Map<String,Object> envelope=repo.save(operation,"analysis","plan-1",7L,data);resultId=(String)envelope.get("resultId");
            context.revision=7L;core.saveReceipt(context,namespace,"request-hash",new Outcome(null,null,envelope));tx.commit();
        }
    }
    @AfterEach void close(){if(storage!=null)storage.close();if(management!=null)management.shutdown();if(database!=null)ServerContext.detach(database.databaseId().databaseId().uuid());if(previousConfig==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previousConfig);}

    @Test void storesPagedNestedAndChunkedValuesAndSupportsExactLargeKeyLookup(){
        try(var tx=database.beginTx()){
            var context=context(tx,actor);var repo=new ImportResultRepository(context);Map<String,Object> envelope=repo.envelope(resultId);Object lease=envelope.get("leaseId");assertEquals(ImportResultContract.FORMAT,envelope.get("format"));assertFalse(envelope.containsKey("objectMappings"));Node receipt=new CoreRepository(tx,context.timestamp).receipt(namespace,operation);assertEquals(resultId,receipt.getProperty("importResultId"));assertFalse(receipt.hasProperty("outcome"));
            Map<String,Object> page=repo.page(Json.map("resultId",resultId,"leaseId",lease,"collection","nested","cursor",null,"limit",10L));List<?> entries=(List<?>)page.get("entries");Map<String,Object> first=ValueCodec.map(entries.get(0));Map<String,Object> keyDescriptor=ValueCodec.map(first.get("key"));assertEquals("text",keyDescriptor.get("type"));assertTrue(((String)keyDescriptor.get("collection")).length()>10);
            assertThrows(ExceptionHandler.DepositException.class,()->repo.entry(Json.map("resultId",resultId,"leaseId",lease,"collection","nested","key",largeKey)));
            Map<String,Object> nestedText=null;for(Object raw:entries){Map<String,Object> entry=ValueCodec.map(raw);if("text".equals(ValueCodec.map(entry.get("key")).get("value")))nestedText=ValueCodec.map(entry.get("value"));}
            assertNotNull(nestedText);assertEquals("text",nestedText.get("type"));String textCollection=(String)nestedText.get("collection");Map<String,Object> textPage=repo.page(Json.map("resultId",resultId,"leaseId",lease,"collection",textCollection,"cursor",null,"limit",10L));var chunks=(List<?>)textPage.get("entries");assertFalse(chunks.isEmpty());String chunk=(String)ValueCodec.map(chunks.getFirst()).get("value");assertTrue(chunk.startsWith("λ🙂"));assertTrue(chunk.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=ImportResultRepository.TEXT_CHUNK_BYTES);
            Map<String,Object> mapping=repo.mappingView(resultId,"objectMappings");assertTrue(mapping.containsKey("source-1"));assertNull(mapping.get("source-1"));
            Map<String,Object> nested=repo.mappingView(resultId,"nested");assertTrue(nested.containsKey(largeKey));assertEquals("large-key-value",nested.get(largeKey));assertEquals(largeKey,nested.entrySet().iterator().next().getKey());tx.commit();
        }
    }

    @Test void completedApplyResultWithPlanIdIsPersistedAndReplayedAsApplied(){
        String applyOperation=UUID.randomUUID().toString();
        Map<String,Object> envelope;
        try(var tx=database.beginTx()){
            var context=context(tx,actor);
            context.operationId=applyOperation;
            context.revision=8L;
            envelope=new ImportResultRepository(context).save(applyOperation,"apply","plan-1",8L,
                    Map.of());
            new CoreRepository(tx,context.timestamp).saveReceipt(context,namespace,"apply-request-hash",
                    new Outcome(null,null,envelope));
            tx.commit();
        }
        try(var tx=database.beginTx()){
            var context=context(tx,actor);
            var core=new CoreRepository(tx,context.timestamp);
            var receipt=core.receipt(namespace,applyOperation);
            assertEquals(envelope.get("resultId"),receipt.getProperty("importResultId"));
            assertFalse(receipt.hasProperty("outcome"));
            assertEquals(withoutLease(envelope), withoutLease(new ImportResultRepository(context).envelope((String)envelope.get("resultId"))));
        }
    }

    @Test void cloneResultIsPagedFromTheServerReceiptAndBoundToItsActor() {
        String cloneOperation=UUID.randomUUID().toString(),cloneNamespace=UUID.randomUUID().toString(),cloneResult;
        try(var tx=database.beginTx()) {
            var context=context(tx,actor);context.operationId=cloneOperation;context.namespaceId=null;context.revision=0;
            var repository=new ImportResultRepository(context);
            createNamespace(tx,cloneNamespace);
            new CoreRepository(tx,context.timestamp).setRole(actor,cloneNamespace,"owner");
            var envelope=repository.saveClone(cloneOperation,cloneNamespace,0,
                    Json.map("name","Cloned","mapping",Json.map("source-a","target-a"),"identityMappings",Json.map("Namespace",Json.map("namespace-a",cloneNamespace),"Object",Json.map("source-a","target-a","source-b","target-b")),
                            "resourceMapping",Json.map("source-resource","target-resource"),"createdObjects",1L));
            cloneResult=(String)envelope.get("resultId");
            new CoreRepository(tx,context.timestamp).saveReceipt(context,"server","clone-request-hash",new Outcome(null,null,envelope));
            tx.commit();
        }
        try(var tx=database.beginTx()) {
            var context=context(tx,actor);context.namespaceId=null;var repository=new ImportResultRepository(context);
            Object lease=repository.envelope(cloneResult).get("leaseId");
            assertEquals(cloneNamespace,repository.envelope(cloneResult).get("namespaceId"));
            assertEquals(Set.of("identityMappings","mapping","metadata","resourceMapping"),ValueCodec.map(repository.envelope(cloneResult).get("collections")).keySet());
            assertEquals(2L,ValueCodec.map(ValueCodec.map(repository.envelope(cloneResult).get("collections")).get("metadata")).get("count"));
            var first=repository.page(Json.map("resultId",cloneResult,"leaseId",lease,"collection","identityMappings","cursor",null,"limit",1));
            assertEquals(1,((List<?>)first.get("entries")).size());assertNotNull(first.get("nextCursor"));
            var second=repository.page(Json.map("resultId",cloneResult,"leaseId",lease,"collection","identityMappings","cursor",first.get("nextCursor"),"limit",1));
            assertEquals(1,((List<?>)second.get("entries")).size());assertNull(second.get("nextCursor"));
            var byKindPage=repository.page(Json.map("resultId",cloneResult,"leaseId",lease,"collection","identityMappings","cursor",null,"limit",128));
            var firstKind=ValueCodec.map(((List<?>)byKindPage.get("entries")).get(0));
            var firstValue=ValueCodec.map(firstKind.get("value"));
            assertEquals("mapping",firstValue.get("type"));
            var nestedEntry=repository.entry(Json.map("resultId",cloneResult,"leaseId",lease,"collection",firstValue.get("collection"),"key","namespace-a"));
            assertEquals(cloneNamespace,ValueCodec.map(nestedEntry.get("value")).get("value"));
            assertEquals("Namespace",ValueCodec.map(firstKind.get("key")).get("value"));
            assertEquals(2,((List<?>)repository.page(Json.map("resultId",cloneResult,"leaseId",lease,"collection","metadata","cursor",null,"limit",128)).get("entries")).size());
            assertNotEquals(first.get("entries"),second.get("entries"));
            var entry=repository.entry(Json.map("resultId",cloneResult,"leaseId",lease,"collection","resourceMapping","key","source-resource"));
            assertEquals("target-resource",ValueCodec.map(entry.get("value")).get("value"));tx.commit();
        }
        try(var tx=database.beginTx()) {
            var context=context(tx,UUID.randomUUID().toString());context.namespaceId=null;
            assertThrows(ExceptionHandler.DepositException.class,()->new ImportResultRepository(context).envelope(cloneResult));tx.rollback();
        }
    }

    @Test void pageCursorsBindResultCollectionAndPositionAndLimitsAreBounded(){
        try(var tx=database.beginTx()){
            var repo=new ImportResultRepository(context(tx,actor));Object lease=repo.envelope(resultId).get("leaseId");Map<String,Object> first=repo.page(Json.map("resultId",resultId,"leaseId",lease,"collection","groups","cursor",null,"limit",1));Map<String,Object> cursor=ValueCodec.map(first.get("nextCursor"));assertNotNull(cursor);assertEquals(1,((List<?>)first.get("entries")).size());assertEquals(Set.of("resultId","collection","entries","nextCursor"),first.keySet());
            Map<String,Object> second=repo.page(Json.map("resultId",resultId,"leaseId",lease,"collection","groups","cursor",cursor,"limit",1));assertEquals(1,((List<?>)second.get("entries")).size());assertNotNull(second.get("nextCursor"));
            assertEquals(Set.of("resultId","leaseId","collection","position","integrity"),cursor.keySet());
            Map<String,Object> third=repo.page(Json.map("resultId",resultId,"leaseId",lease,"collection","groups","cursor",second.get("nextCursor"),"limit",1));
            assertTrue(ValueCodec.map(((List<?>)third.get("entries")).getFirst()).containsKey("collection"));
            assertNull(third.get("nextCursor"));
            assertThrows(ExceptionHandler.DepositException.class,()->repo.page(Json.map("resultId",resultId,"leaseId",lease,"collection","nested","cursor",cursor,"limit",1)));
            assertThrows(ExceptionHandler.DepositException.class,()->repo.page(Json.map("resultId",resultId,"leaseId",lease,"collection","groups","cursor",new LinkedHashMap<>(cursor){{put("integrity","malformed");}},"limit",1)));
            assertThrows(ExceptionHandler.DepositException.class,()->repo.page(Json.map("resultId",resultId,"leaseId",lease,"collection","groups","cursor",Json.map("resultId",resultId,"collection","groups","position",2L),"limit",1)));
            assertThrows(ExceptionHandler.DepositException.class,()->repo.page(Json.map("resultId",resultId,"leaseId",lease,"collection","groups","cursor","opaque-legacy-token","limit",1)));
            assertThrows(ExceptionHandler.DepositException.class,()->repo.page(Json.map("resultId",resultId,"leaseId",lease,"collection","groups","cursor",null,"limit",257)));tx.rollback();
        }
    }

    @Test void readsRequireTheOriginalReceiptActorAndNamespace(){
        try(var tx=database.beginTx()){
            var repo=new ImportResultRepository(context(tx,UUID.randomUUID().toString()));assertThrows(ExceptionHandler.DepositException.class,()->repo.envelope(resultId));tx.rollback();
        }
        try(var tx=database.beginTx()){
            var context=context(tx,actor);context.namespaceId=UUID.randomUUID().toString();var repo=new ImportResultRepository(context);assertEquals(resultId,repo.envelope(resultId).get("resultId"));tx.rollback();
        }
        try(var tx=database.beginTx()){
            Object lease=new ImportResultRepository(context(tx,actor)).envelope(resultId).get("leaseId");
            var repo=new ImportResultRepository(context(tx,UUID.randomUUID().toString()));assertThrows(ExceptionHandler.DepositException.class,()->repo.page(Json.map("resultId",resultId,"leaseId",lease,"collection","groups","cursor",null,"limit",1L)));tx.rollback();
        }
    }

    @Test void largeConflictDetailsStayPagedAndCollectionCountsComeFromStreaming(){
        SchemaRepository.initialize(database);String diagnosticOperation=UUID.randomUUID().toString();String diagnosticResult;
        try(var tx=database.beginTx();var scratch=new DiskJsonList(temporary,"conflicts-");var detailRows=new DiskJsonList(temporary,"pattern-details-")){
            for(int i=0;i<600;i++)detailRows.add(Json.map("name","Pattern-"+i,"objects",List.of("role-"+i)));
            scratch.add(Json.map("kind","patternCompatibility","_conflictRef","patterns"));
            var conflicts=ImportSnapshotAnalysis.conflictView(scratch,List.of(),detailRows,new AbstractSet<>(){
                public int size(){return 600;}public Iterator<String> iterator(){return java.util.stream.IntStream.range(0,600).mapToObj(i->"Class-"+i).iterator();}
            });
            assertTrue(Json.write(scratch.getFirst()).length()<100,"scratch conflict row must contain only scalar reference data");
            var rowMetadata=new LinkedHashMap<String,Object>();for(int i=0;i<600;i++)rowMetadata.put("source-"+i,Json.map("blank",i%2==0));
            var context=context(tx,actor);context.operationId=diagnosticOperation;var repository=new ImportResultRepository(context);
            var envelope=repository.save(diagnosticOperation,"analysis",null,7L,Json.map("conflicts",conflicts,"_tabularBlanks",rowMetadata));diagnosticResult=(String)envelope.get("resultId");assertEquals("IMPORT_CONFLICT",envelope.get("state"));
            context.revision=7L;var core=new CoreRepository(tx,context.timestamp);core.saveReceipt(context,namespace,"diagnostic-request",new Outcome(null,null,envelope));assertEquals(diagnosticResult,core.receipt(namespace,diagnosticOperation).getProperty("importResultId"));assertNull(tx.findNode(Label.label("DepositImportPlan"),"resultId",diagnosticResult));tx.commit();
        }catch(Exception failure){throw new AssertionError(failure);}
        try(var tx=database.beginTx()){
            var repository=new ImportResultRepository(context(tx,actor));Object lease=repository.envelope(diagnosticResult).get("leaseId");var first=repository.page(Json.map("resultId",diagnosticResult,"leaseId",lease,"collection","conflicts","cursor",null,"limit",1L));
            var conflictDescriptor=ValueCodec.map(((List<?>)first.get("entries")).getFirst());
            var conflictFields=repository.page(Json.map("resultId",diagnosticResult,"leaseId",lease,"collection",conflictDescriptor.get("collection"),"cursor",null,"limit",10L));Map<String,Object> details=null;
            for(Object raw:(List<?>)conflictFields.get("entries")){var field=ValueCodec.map(raw);var key=ValueCodec.map(field.get("key"));if("patterns".equals(key.get("value")))details=ValueCodec.map(field.get("value"));}
            assertNotNull(details);assertEquals(600L,details.get("count"));String detailCollection=(String)details.get("collection");
            var page=repository.page(Json.map("resultId",diagnosticResult,"leaseId",lease,"collection",detailCollection,"cursor",null,"limit",1L));assertEquals(1,((List<?>)page.get("entries")).size());assertNotNull(page.get("nextCursor"));tx.commit();
        }
        try(var tx=database.beginTx()){
            var view=new ImportResultRepository(context(tx,actor)).mappingView(diagnosticResult,"_tabularBlanks");var iterator=view.entrySet().iterator();int count=0;String first=null,last=null;while(iterator.hasNext()){String key=iterator.next().getKey();if(count==0)first=key;last=key;count++;}
            assertEquals(600,count);assertEquals("source-0",first);assertEquals("source-599",last);
            assertTrue(view.containsKey("source-599"),"the final hash-key bucket must remain readable without full collection scans");
            String explain="EXPLAIN MATCH (row:DepositImportResultEntry {resultId:$resultId,collectionId:$collectionId}) WHERE row.position >= $position RETURN row ORDER BY row.position LIMIT $limit";
            var query=tx.execute(explain,Map.of("resultId",diagnosticResult,"collectionId","fixture","position",0L,"limit",128L));String detail=query.getExecutionPlanDescription().toString();query.close();assertTrue(detail.contains("NodeIndexSeek"),detail);assertFalse(detail.contains("NodeByLabelScan"),detail);tx.rollback();
        }
        try(var tx=database.beginTx()){
            String operationId=UUID.randomUUID().toString();var context=new RequestContext(tx,Map.of("token","test-token","operationId",operationId));context.actorId=actor;context.namespaceId=namespace;var repo=new ImportResultRepository(context);
            Map<String,Object> reportedOversized=new AbstractMap<>(){
                public Set<Entry<String,Object>> entrySet(){return new AbstractSet<>(){public int size(){return Integer.MAX_VALUE;}public Iterator<Entry<String,Object>> iterator(){return List.<Entry<String,Object>>of(new AbstractMap.SimpleImmutableEntry<>("a","1"),new AbstractMap.SimpleImmutableEntry<>("b","2")).iterator();}};}
            };
            var envelope=repo.save(operationId,"analysis","plan",7L,Map.of("objectMappings",reportedOversized));
            assertEquals(2L,ValueCodec.map(ValueCodec.map(envelope.get("collections")).get("objectMappings")).get("count"));tx.rollback();
        }
    }

    @Test void mappedIncomingObjectLookupUsesCompositeResultIndex()throws java.security.NoSuchAlgorithmException{
        String incoming="source-object-299";
        try(var tx=database.beginTx()){
            var context=context(tx,actor);
            var explanation=tx.execute("EXPLAIN MATCH (row:DepositImportResultEntry {resultId:$resultId,collectionId:$collectionId,mapKeyHash:$mapKeyHash,mapKey:$mapKey}) RETURN row",
                    Map.of("resultId",resultId,"collectionId",incoming,"mapKeyHash",keyHash(incoming),"mapKey",incoming));
            String detail=explanation.getExecutionPlanDescription().toString();explanation.close();
            assertTrue(detail.contains("NodeIndexSeek"),detail);assertFalse(detail.contains("PropertyFilteringIterator"),detail);
            Map<String,Object> mappings=new ImportResultRepository(context).mappingView(resultId,"objectMappings");String target=(String)mappings.get(incoming);
            assertNotNull(target);assertFalse(mappings.containsKey("source-missing-object"));
            tx.rollback();
        }
    }
    private static String keyHash(String key)throws java.security.NoSuchAlgorithmException{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}

    private void createNamespace(Transaction tx,String id) {
        var core=new CoreRepository(tx,java.time.ZonedDateTime.now());
        core.createRestored("Namespace",null,actor,Json.map("name",id,"sequence",0L,"historyMode","disabled"),id);
    }

    static Map<String,Object> withoutLease(Map<String,Object> result) {
        var envelope=new LinkedHashMap<>(result);
        envelope.remove("leaseId");
        envelope.remove("leaseExpiresAt");
        return envelope;
    }

    private RequestContext context(Transaction tx,String who){var context=new RequestContext(tx,Map.of("token","test-token","operationId",operation));context.actorId=who;context.namespaceId=namespace;context.state.scoped=true;return context;}
}
