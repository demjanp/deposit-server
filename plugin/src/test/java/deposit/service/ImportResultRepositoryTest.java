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
        try(var tx=database.beginTx()){
            var context=context(tx,actor);var repo=new ImportResultRepository(context);
            String longText="λ🙂".repeat(12_000);
            Map<String,Object> data=Json.map("objectMappings",Json.map("source-1",null),"nested",Json.map(largeKey,"large-key-value","text",longText),"groups",List.of(Json.map("name","one"),Json.map("name","two"),Json.map("name","three")));
            Map<String,Object> envelope=repo.save(operation,"analysis","plan-1",7L,System.currentTimeMillis()+60_000,data);resultId=(String)envelope.get("resultId");
            context.revision=7L;var core=new CoreRepository(tx,java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC));core.saveReceipt(context,namespace,"request-hash",new Outcome(null,null,envelope));tx.commit();
        }
    }
    @AfterEach void close(){if(storage!=null)storage.close();if(management!=null)management.shutdown();if(database!=null)ServerContext.detach(database.databaseId().databaseId().uuid());if(previousConfig==null)System.clearProperty("deposit.config");else System.setProperty("deposit.config",previousConfig);}

    @Test void storesPagedNestedAndChunkedValuesAndSupportsExactLargeKeyLookup(){
        try(var tx=database.beginTx()){
            var context=context(tx,actor);var repo=new ImportResultRepository(context);Map<String,Object> envelope=repo.envelope(resultId);assertEquals(ImportResultContract.FORMAT,envelope.get("format"));assertFalse(envelope.containsKey("objectMappings"));Node receipt=new CoreRepository(tx,context.timestamp).receipt(namespace,operation);assertEquals(resultId,receipt.getProperty("importResultId"));assertEquals(resultId,new CoreRepository(tx,context.timestamp).replay(receipt,namespace).data().get("resultId"));
            Map<String,Object> page=repo.page(Json.map("resultId",resultId,"collection","nested","cursor",null,"limit",10L));List<?> entries=(List<?>)page.get("entries");Map<String,Object> first=ValueCodec.map(entries.get(0));Map<String,Object> keyDescriptor=ValueCodec.map(first.get("key"));assertEquals("text",keyDescriptor.get("type"));assertTrue(((String)keyDescriptor.get("collection")).length()>10);
            assertThrows(ExceptionHandler.DepositException.class,()->repo.entry(Json.map("resultId",resultId,"collection","nested","key",largeKey)));
            Map<String,Object> nestedText=null;for(Object raw:entries){Map<String,Object> entry=ValueCodec.map(raw);if("text".equals(ValueCodec.map(entry.get("key")).get("value")))nestedText=ValueCodec.map(entry.get("value"));}
            assertNotNull(nestedText);assertEquals("text",nestedText.get("type"));String textCollection=(String)nestedText.get("collection");Map<String,Object> textPage=repo.page(Json.map("resultId",resultId,"collection",textCollection,"cursor",null,"limit",10L));var chunks=(List<?>)textPage.get("entries");assertFalse(chunks.isEmpty());String chunk=(String)ValueCodec.map(chunks.getFirst()).get("value");assertTrue(chunk.startsWith("λ🙂"));assertTrue(chunk.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=ImportResultRepository.TEXT_CHUNK_BYTES);
            Map<String,Object> mapping=repo.mappingView(resultId,"objectMappings");assertTrue(mapping.containsKey("source-1"));assertNull(mapping.get("source-1"));
            Map<String,Object> nested=repo.mappingView(resultId,"nested");assertTrue(nested.containsKey(largeKey));assertEquals("large-key-value",nested.get(largeKey));assertEquals(largeKey,nested.entrySet().iterator().next().getKey());tx.commit();
        }
    }

    @Test void pageCursorsBindResultCollectionAndPositionAndLimitsAreBounded(){
        try(var tx=database.beginTx()){
            var repo=new ImportResultRepository(context(tx,actor));Map<String,Object> first=repo.page(Json.map("resultId",resultId,"collection","groups","cursor",null,"limit",1L));String cursor=(String)first.get("nextCursor");assertNotNull(cursor);assertEquals(1,((List<?>)first.get("entries")).size());assertEquals(Set.of("resultId","collection","entries","nextCursor"),first.keySet());
            Map<String,Object> second=repo.page(Json.map("resultId",resultId,"collection","groups","cursor",cursor,"limit",1L));assertEquals(1,((List<?>)second.get("entries")).size());assertNotNull(second.get("nextCursor"));
            assertThrows(ExceptionHandler.DepositException.class,()->repo.page(Json.map("resultId",resultId,"collection","nested","cursor",cursor,"limit",1L)));
            assertThrows(ExceptionHandler.DepositException.class,()->repo.page(Json.map("resultId",resultId,"collection","groups","cursor",null,"limit",257L)));tx.rollback();
        }
    }

    @Test void readsRequireTheOriginalReceiptActorAndNamespace(){
        try(var tx=database.beginTx()){
            var repo=new ImportResultRepository(context(tx,UUID.randomUUID().toString()));assertThrows(ExceptionHandler.DepositException.class,()->repo.envelope(resultId));tx.rollback();
        }
        try(var tx=database.beginTx()){
            var context=context(tx,actor);context.namespaceId=UUID.randomUUID().toString();var repo=new ImportResultRepository(context);assertThrows(ExceptionHandler.DepositException.class,()->repo.envelope(resultId));tx.rollback();
        }
        try(var tx=database.beginTx()){
            var repo=new ImportResultRepository(context(tx,UUID.randomUUID().toString()));assertThrows(ExceptionHandler.DepositException.class,()->repo.page(Json.map("resultId",resultId,"collection","groups","cursor",null,"limit",1L)));tx.rollback();
        }
    }

    @Test void planInspectionKeepsAnalysisEnvelopeImmutableAndReturnsLifecycleSeparately(){
        try(var tx=database.beginTx()){
            var context=context(tx,actor);var plan=tx.createNode(Label.label("DepositImportPlan"));
            plan.setProperty("id","plan-1");plan.setProperty("namespaceId",namespace);plan.setProperty("state","applied");
            plan.setProperty("targetRevision",8L);plan.setProperty("expiresAt",123456L);plan.setProperty("resultId",resultId);
            Map<String,Object> before=new ImportResultRepository(context).envelope(resultId);
            Map<String,Object> inspected=ImportService.planData(plan,before);
            assertFalse(inspected.containsKey("format"));
            assertEquals(Set.of("plan","analysisResult"),inspected.keySet());
            assertEquals(Json.map("planId","plan-1","namespaceId",namespace,"state","applied","targetRevision",8L,"expiresAt",123456L),inspected.get("plan"));
            assertEquals(before,inspected.get("analysisResult"));
            assertEquals("ready",ValueCodec.map(inspected.get("analysisResult")).get("state"));
            tx.rollback();
        }
    }

    @Test void namespaceOwnerCanSeePlanLifecycleButCannotReadAnotherActorsResultPages(){
        try(var tx=database.beginTx()){
            var ownerContext=context(tx,UUID.randomUUID().toString());var repo=new ImportResultRepository(ownerContext);
            assertEquals(resultId,repo.envelopeForPlan(resultId,"plan-1",namespace).get("resultId"));
            assertThrows(ExceptionHandler.DepositException.class,()->repo.page(Json.map("resultId",resultId,"collection","groups","cursor",null,"limit",1L)));
            tx.rollback();
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
            var context=new RequestContext(tx,Map.of("token","test-token","operationId",diagnosticOperation));context.actorId=actor;context.namespaceId=namespace;var repository=new ImportResultRepository(context);
            var envelope=repository.save(diagnosticOperation,"diagnostic",null,7L,System.currentTimeMillis()+60_000,Json.map("conflicts",conflicts,"_tabularBlanks",rowMetadata));diagnosticResult=(String)envelope.get("resultId");
            context.revision=7L;var core=new CoreRepository(tx,context.timestamp);core.saveReceipt(context,namespace,"diagnostic-request",new Outcome(null,null,envelope));assertEquals(diagnosticResult,core.replay(core.receipt(namespace,diagnosticOperation),namespace).data().get("resultId"));assertNull(tx.findNode(Label.label("DepositImportPlan"),"resultId",diagnosticResult));tx.commit();
        }catch(Exception failure){throw new AssertionError(failure);}
        try(var tx=database.beginTx()){
            var repository=new ImportResultRepository(context(tx,actor));var first=repository.page(Json.map("resultId",diagnosticResult,"collection","conflicts","cursor",null,"limit",1L));
            var conflictDescriptor=ValueCodec.map(((List<?>)first.get("entries")).getFirst());
            var conflictFields=repository.page(Json.map("resultId",diagnosticResult,"collection",conflictDescriptor.get("collection"),"cursor",null,"limit",10L));Map<String,Object> details=null;
            for(Object raw:(List<?>)conflictFields.get("entries")){var field=ValueCodec.map(raw);var key=ValueCodec.map(field.get("key"));if("patterns".equals(key.get("value")))details=ValueCodec.map(field.get("value"));}
            assertNotNull(details);assertEquals(600L,details.get("count"));String detailCollection=(String)details.get("collection");
            var page=repository.page(Json.map("resultId",diagnosticResult,"collection",detailCollection,"cursor",null,"limit",1L));assertEquals(1,((List<?>)page.get("entries")).size());assertNotNull(page.get("nextCursor"));tx.commit();
        }
        try(var tx=database.beginTx()){
            var view=new ImportResultRepository(context(tx,actor)).mappingView(diagnosticResult,"_tabularBlanks");var iterator=view.entrySet().iterator();int count=0;String first=null,last=null;while(iterator.hasNext()){String key=iterator.next().getKey();if(count==0)first=key;last=key;count++;}
            assertEquals(600,count);assertEquals("source-0",first);assertEquals("source-599",last);
            String explain="EXPLAIN MATCH (row:DepositImportResultEntry {resultId:$resultId,collectionId:$collectionId}) WHERE row.position >= $position RETURN row ORDER BY row.position LIMIT $limit";
            var query=tx.execute(explain,Map.of("resultId",diagnosticResult,"collectionId","fixture","position",0L,"limit",128L));String detail=query.getExecutionPlanDescription().toString();query.close();assertTrue(detail.contains("NodeIndexSeek"),detail);assertFalse(detail.contains("NodeByLabelScan"),detail);tx.rollback();
        }
        try(var tx=database.beginTx()){
            String operationId=UUID.randomUUID().toString();var context=new RequestContext(tx,Map.of("token","test-token","operationId",operationId));context.actorId=actor;context.namespaceId=namespace;var repo=new ImportResultRepository(context);
            Map<String,Object> reportedOversized=new AbstractMap<>(){
                public Set<Entry<String,Object>> entrySet(){return new AbstractSet<>(){public int size(){return Integer.MAX_VALUE;}public Iterator<Entry<String,Object>> iterator(){return List.<Entry<String,Object>>of(new AbstractMap.SimpleImmutableEntry<>("a","1"),new AbstractMap.SimpleImmutableEntry<>("b","2")).iterator();}};}
            };
            var envelope=repo.save(operationId,"analysis","plan",7L,System.currentTimeMillis()+60_000,Map.of("objectMappings",reportedOversized));
            assertEquals(2L,ValueCodec.map(ValueCodec.map(envelope.get("collections")).get("objectMappings")).get("count"));tx.rollback();
        }
    }

    private RequestContext context(Transaction tx,String who){var context=new RequestContext(tx,Map.of("token","test-token","operationId",operation));context.actorId=who;context.namespaceId=namespace;return context;}
}
