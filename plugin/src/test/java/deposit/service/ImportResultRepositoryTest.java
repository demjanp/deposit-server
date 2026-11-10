package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.*;
import deposit.common.Results.Outcome;
import deposit.repository.CoreRepository;
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
    final String actor=UUID.randomUUID().toString(),namespace=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();
    String resultId,largeKey;

    @BeforeEach void setup()throws Exception {
        management=new DatabaseManagementServiceBuilderImplementation(temporary.resolve("db"),type->type!=DepositExtension.class).build();
        database=(GraphDatabaseAPI)management.database("neo4j");server=ServerContext.attach(database.databaseId().databaseId().uuid());var ready=ServerContext.class.getDeclaredMethod("markReadyForTesting");ready.setAccessible(true);ready.invoke(server);
        largeKey="🔐".repeat(5_000);
        try(var tx=database.beginTx()){
            var context=context(tx,actor);var repo=new ImportResultRepository(context);
            String longText="λ🙂".repeat(12_000);
            Map<String,Object> data=Json.map("objectMappings",Json.map("source-1",null),"nested",Json.map(largeKey,"large-key-value","text",longText),"groups",List.of(Json.map("name","one"),Json.map("name","two"),Json.map("name","three")));
            Map<String,Object> envelope=repo.save(operation,"analysis","plan-1",7L,System.currentTimeMillis()+60_000,data);resultId=(String)envelope.get("resultId");
            context.revision=7L;var core=new CoreRepository(tx,java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC));core.saveReceipt(context,namespace,"request-hash",new Outcome(null,null,envelope));tx.commit();
        }
    }
    @AfterEach void close(){if(management!=null)management.shutdown();}

    @Test void storesPagedNestedAndChunkedValuesAndSupportsExactLargeKeyLookup(){
        try(var tx=database.beginTx()){
            var context=context(tx,actor);var repo=new ImportResultRepository(context);Map<String,Object> envelope=repo.envelope(resultId);assertEquals(ImportResultRepository.FORMAT,envelope.get("format"));assertFalse(envelope.containsKey("objectMappings"));Node receipt=new CoreRepository(tx,context.timestamp).receipt(namespace,operation);assertEquals(resultId,receipt.getProperty("importResultId"));assertEquals(resultId,new CoreRepository(tx,context.timestamp).replay(receipt,namespace).data().get("resultId"));
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
            var repo=new ImportResultRepository(context(tx,actor));Map<String,Object> first=repo.page(Json.map("resultId",resultId,"collection","groups","cursor",null,"limit",1L));String cursor=(String)first.get("nextCursor");assertNotNull(cursor);assertEquals(1,((List<?>)first.get("entries")).size());
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
            assertEquals("deposit.import-plan/1",inspected.get("format"));
            assertEquals(Json.map("planId","plan-1","namespaceId",namespace,"state","applied","targetRevision",8L,"expiresAt",123456L),inspected.get("plan"));
            assertEquals(before,inspected.get("analysisResult"));
            assertEquals("ready",ValueCodec.map(inspected.get("analysisResult")).get("state"));
            tx.rollback();
        }
    }

    private RequestContext context(Transaction tx,String who){var context=new RequestContext(tx,Map.of("token","test-token","operationId",operation));context.actorId=who;context.namespaceId=namespace;return context;}
}
